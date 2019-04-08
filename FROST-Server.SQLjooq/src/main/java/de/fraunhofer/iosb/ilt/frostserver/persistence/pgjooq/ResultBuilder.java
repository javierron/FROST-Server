/*
 * Copyright (C) 2016 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq;

import com.fasterxml.jackson.databind.node.ArrayNode;
import de.fraunhofer.iosb.ilt.frostserver.model.core.Entity;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySet;
import de.fraunhofer.iosb.ilt.frostserver.model.core.NavigableElement;
import de.fraunhofer.iosb.ilt.frostserver.path.CustomPropertyArrayIndex;
import de.fraunhofer.iosb.ilt.frostserver.path.CustomPropertyPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.EntitySetPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.NavigationProperty;
import de.fraunhofer.iosb.ilt.frostserver.path.PropertyPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.ResourcePath;
import de.fraunhofer.iosb.ilt.frostserver.path.ResourcePathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.ResourcePathVisitor;
import de.fraunhofer.iosb.ilt.frostserver.persistence.pgjooq.factories.EntityFactory;
import de.fraunhofer.iosb.ilt.frostserver.query.Expand;
import de.fraunhofer.iosb.ilt.frostserver.query.Query;
import de.fraunhofer.iosb.ilt.frostserver.settings.PersistenceSettings;
import de.fraunhofer.iosb.ilt.frostserver.util.UrlHelper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.Cursor;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.ResultQuery;
import org.jooq.conf.ParamType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the sqlQuery into the model instances to be returned to the client.
 *
 * @author scf
 */
public class ResultBuilder implements ResourcePathVisitor {

    /**
     * The logger for this class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ResultBuilder.class);
    private final PostgresPersistenceManager pm;
    private final PersistenceSettings persistenceSettings;
    private final ResourcePath path;
    private final Query staQuery;
    private final QueryBuilder sqlQueryBuilder;
    private final ResultQuery<Record> sqlQuery;

    private Object resultObject;
    /**
     * If resultObject is a property or sub-property, and we are not using
     * $value, then the resultObject is encapsulated in a Map, using this key.
     */
    private String entityName;

    /**
     *
     * @param pm The persistence manager.
     * @param path The path leading to the items.
     * @param query The query parameters to use when fetching expanded items.
     * @param sqlQueryBuilder The configured sql query builder to use for
     * generating select and count queries.
     */
    public ResultBuilder(PostgresPersistenceManager pm, ResourcePath path, Query query, QueryBuilder sqlQueryBuilder) {
        this.pm = pm;
        this.path = path;
        this.staQuery = query;
        this.sqlQueryBuilder = sqlQueryBuilder;
        this.sqlQuery = sqlQueryBuilder.buildSelect();
        this.persistenceSettings = pm.getCoreSettings().getPersistenceSettings();
    }

    public Object getEntity() {
        return resultObject;
    }

    /**
     * If resultObject is a property or sub-property, and we are not using
     * $value, then the resultObject is encapsulated in a Map, using this key.
     *
     * @return The entitiyName of the resultObject in the map.
     */
    public String getEntityName() {
        return entityName;
    }

    @Override
    public void visit(EntityPathElement element) {
        Result<Record> results = sqlQuery.fetch();
        if (results.size() > 1) {
            throw new IllegalStateException("Expecting an element, yet more than 1 result. Got " + results.size() + " results.");
        }
        if (results.isEmpty()) {
            return;
        }

        EntityFactory factory;
        factory = pm.getEntityFactories().getFactoryFor(element.getEntityType());
        Entity entity = factory.create(results.get(0), staQuery, new DataSize());

        if (entity == null) {
            throw new IllegalStateException("Failed to create an entity from result set.");
        }
        expandEntity(entity, staQuery);
        resultObject = entity;
    }

    private void expandEntity(Entity entity, Query query) {
        if (query == null) {
            return;
        }
        for (Expand expand : query.getExpand()) {
            addExpandToEntity(entity, expand, query);
        }
    }

    private void addExpandToEntity(Entity entity, Expand expand, Query query1) {
        ResourcePath ePath = new ResourcePath(path.getServiceRootUrl(), null);
        ResourcePathElement parentCollection = new EntitySetPathElement(entity.getEntityType(), null);
        ePath.addPathElement(parentCollection, false, false);
        ResourcePathElement parent = new EntityPathElement(entity.getId(), entity.getEntityType(), parentCollection);
        ePath.addPathElement(parent, false, true);
        NavigationProperty firstNp = expand.getPath().get(0);
        NavigableElement existing = null;
        Object o = entity.getProperty(firstNp);
        if (o instanceof NavigableElement) {
            existing = (NavigableElement) o;
        }
        if (firstNp.isSet) {
            EntitySetPathElement child = new EntitySetPathElement(firstNp.type, parent);
            ePath.addPathElement(child, true, false);
        } else {
            EntityPathElement child = new EntityPathElement(null, firstNp.type, parent);
            ePath.addPathElement(child, true, false);
        }
        Object child;
        Query subQuery;
        if (expand.getPath().size() == 1) {
            // This was the last element in the expand path. The query is for this element.
            subQuery = expand.getSubQuery();
            if (subQuery == null) {
                subQuery = new Query(query1.getSettings());
            }
        } else {
            // This is not the last element in the expand path. The query is not for this element.
            subQuery = new Query(query1.getSettings());
            Expand subExpand = new Expand();
            subExpand.getPath().addAll(expand.getPath());
            subExpand.getPath().remove(0);
            subExpand.setSubQuery(expand.getSubQuery());
            subQuery.addExpand(subExpand);
            if (query1.getCount().isPresent()) {
                subQuery.setCount(query1.isCountOrDefault());
            }
        }
        if (existing == null || !existing.isExportObject()) {
            child = pm.get(ePath, subQuery);
            entity.setProperty(firstNp, child);
        } else if (existing instanceof EntitySet) {
            expandEntitySet((EntitySet) existing, subQuery);
        } else if (existing instanceof Entity) {
            expandEntity((Entity) existing, subQuery);
        }
    }

    private void expandEntitySet(EntitySet entitySet, Query subQuery) {
        for (Object subEntity : entitySet) {
            if (subEntity instanceof Entity) {
                expandEntity((Entity) subEntity, subQuery);
            }
        }
    }

    private <R extends Record> Cursor<R> timeQuery(ResultQuery<R> query) {
        if (!persistenceSettings.isLogSlowQueries()) {
            return query.fetchLazy();
        }
        long start = System.currentTimeMillis();
        Cursor<R> result = query.fetchLazy();
        long end = System.currentTimeMillis();
        long duration = end - start;
        if (LOGGER.isInfoEnabled() && duration > persistenceSettings.getSlowQueryThreshold()) {
            LOGGER.info("Slow Query executed in {} ms:\n{}", duration, query.getSQL(ParamType.INLINED));
        }
        return result;
    }

    @Override
    public void visit(EntitySetPathElement element) {
        Cursor<Record> results = timeQuery(sqlQuery);
        EntityFactory factory;
        factory = pm.getEntityFactories().getFactoryFor(element.getEntityType());
        EntitySet<? extends Entity> entitySet = pm.getEntityFactories()
                .createSetFromRecords(factory, results, staQuery, pm.getCoreSettings().getDataSizeMax());

        if (entitySet == null) {
            throw new IllegalStateException("Empty set!");
        }

        if (staQuery.isCountOrDefault()) {
            ResultQuery<Record1<Integer>> countQuery = sqlQueryBuilder.buildCount();
            Integer count = timeQuery(countQuery)
                    .fetchNext()
                    .component1();
            entitySet.setCount(count);
        }

        int entityCount = entitySet.size();
        boolean hasMore = results.hasNext();
        if (entityCount < staQuery.getTopOrDefault() && hasMore) {
            // The loading was aborted, probably due to size constraints.
            staQuery.setTop(entityCount);
        }
        if (hasMore) {
            entitySet.setNextLink(UrlHelper.generateNextLink(path, staQuery));
        }
        for (Entity e : entitySet) {
            expandEntity(e, staQuery);
        }
        resultObject = entitySet;
    }

    @Override
    public void visit(PropertyPathElement element) {
        element.getParent().visit(this);
        if (Entity.class.isAssignableFrom(resultObject.getClass())) {
            Object propertyValue = ((Entity) resultObject).getProperty(element.getProperty());
            Map<String, Object> entityMap = new HashMap<>();
            entityName = element.getProperty().entitiyName;
            entityMap.put(entityName, propertyValue);
            resultObject = entityMap;
        }
    }

    @Override
    public void visit(CustomPropertyPathElement element) {
        element.getParent().visit(this);
        String name = element.getName();
        if (resultObject instanceof Map) {
            Map map = (Map) resultObject;
            Object inner = map.get(entityName);
            if (inner instanceof Map) {
                map = (Map) inner;
                if (map.containsKey(name)) {
                    Object propertyValue = map.get(name);
                    Map<String, Object> entityMap = new HashMap<>();
                    entityName = name;
                    entityMap.put(entityName, propertyValue);
                    resultObject = entityMap;
                    return;
                }
            }
        }

        resultObject = null;
        entityName = null;
    }

    @Override
    public void visit(CustomPropertyArrayIndex element) {
        element.getParent().visit(this);
        int index = element.getIndex();
        if (resultObject instanceof Map) {
            Map map = (Map) resultObject;
            Object inner = map.get(entityName);
            Object propertyValue = null;
            if (inner instanceof ArrayNode && ((ArrayNode) inner).size() > index) {
                propertyValue = ((ArrayNode) inner).get(index);
            }
            if (inner instanceof List && ((List) inner).size() > index) {
                propertyValue = ((List) inner).get(index);
            }
            if (propertyValue != null) {
                Map<String, Object> entityMap = new HashMap<>();
                entityName = entityName + "[" + Integer.toString(index) + "]";
                entityMap.put(entityName, propertyValue);
                resultObject = entityMap;
                return;
            }
        }

        resultObject = null;
        entityName = null;
    }

}