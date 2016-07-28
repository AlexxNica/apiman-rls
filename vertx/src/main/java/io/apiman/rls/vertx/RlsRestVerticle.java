/*
 * Copyright 2016 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apiman.rls.vertx;

import io.apiman.rls.beans.rest.LimitBean;
import io.apiman.rls.beans.rest.LimitExceededErrorBean;
import io.apiman.rls.beans.rest.LimitIncrementBean;
import io.apiman.rls.beans.rest.LimitListBean;
import io.apiman.rls.beans.rest.NewLimitBean;
import io.apiman.rls.beans.rest.RlsInfoBean;
import io.apiman.rls.limits.Limits;
import io.apiman.rls.limits.exceptions.LimitExceededException;
import io.apiman.rls.limits.exceptions.LimitNotFoundException;
import io.apiman.rls.limits.exceptions.LimitPeriodConflictException;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

import java.lang.reflect.InvocationTargetException;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * A verticle that implements the RLS REST API.
 * @author eric.wittmann@gmail.com
 */
public class RlsRestVerticle extends AbstractVerticle {
    
    private Limits limits = Limits.getInstance();

    @SuppressWarnings("nls")
    @Override
    public void start(Future<Void> fut) {
        System.out.println("Starting RLS REST Verticle");
        Router router = Router.router(vertx);

        router.route().handler(BodyHandler.create());
        Json.mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        Json.prettyMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        Json.mapper.registerModule(new JavaTimeModule());
        Json.prettyMapper.registerModule(new JavaTimeModule());

        // Path: /
        router.get("/").handler(this::handleRoot);

        // Path: /limits
        router.get("/limits").handler(this::handleListLimits);
        router.post("/limits").handler(this::createOrIncrementLimit);

        // Path: /limits/:limitId
        router.get("/limits/:limitId").handler(this::handleGetLimit);
        router.put("/limits/:limitId").handler(this::incrementLimit);
        router.delete("/limits/:limitId").handler(this::deleteLimit);

        vertx.createHttpServer().requestHandler(router::accept).listen(8080);
        System.out.println("RLS REST Verticle started");
        fut.complete();
    }

    /**
     * Implements the REST API root response (just some meta-info about the API).
     * @param routingContext
     */
    private void handleRoot(RoutingContext routingContext) {
        System.out.println(routingContext.request().absoluteURI());
        
        RlsInfoBean info = new RlsInfoBean();
        final HttpServerResponse response = routingContext.response();
        sendBeanAsResponse(info, response);
    }

    /**
     * Implements the 'list limits' REST endpoint.
     * 
     * @param routingContext
     */
    private void handleListLimits(RoutingContext routingContext) {
        int page = getQueryParam(routingContext.request(), "page", Integer.class, 1); //$NON-NLS-1$
        int pageSize = getQueryParam(routingContext.request(), "pageSize", Integer.class, 20); //$NON-NLS-1$
        
        final HttpServerResponse response = routingContext.response();
        LimitListBean rval = limits.listLimits(page, pageSize);
        sendBeanAsResponse(rval, response);
    }

    /**
     * Implements the 'create/increment limit' REST endpoint.  This endpoint will
     * either create a new limit, or, if the limit already exists, will increment 
     * it.
     * 
     * @param routingContext
     */
    private void createOrIncrementLimit(RoutingContext routingContext) {
        final ZonedDateTime now = ZonedDateTime.now();
        final HttpServerResponse response = routingContext.response();
        final String body = routingContext.getBodyAsString();
        final NewLimitBean newLimit = Json.decodeValue(body, NewLimitBean.class);
        try {
            LimitBean rval = limits.createLimit(now, newLimit);
            sendBeanAsResponse(rval, response);
        } catch (LimitExceededException e) {
            LimitExceededErrorBean rval = new LimitExceededErrorBean();
            rval.setResetOn(e.getResetOn());
            response.setStatusCode(429);
            sendBeanAsResponse(rval, response);
        } catch (LimitPeriodConflictException e) {
            response.setStatusCode(409);
            response.setStatusMessage("Limit period conflict detected."); //$NON-NLS-1$
            response.end();
        }
    }

    /**
     * Implements the 'get limit' REST endpoint.  Simple returns a limit by id.
     * 
     * @param routingContext
     */
    private void handleGetLimit(RoutingContext routingContext) {
        String limitId = routingContext.request().getParam("limitId"); //$NON-NLS-1$
        System.out.println("Getting limit with id: " + limitId); //$NON-NLS-1$

        final HttpServerResponse response = routingContext.response();
        try {
            LimitBean rval = limits.getLimit(limitId);
            rval.setId(limitId);
            sendBeanAsResponse(rval, response);
        } catch (LimitNotFoundException e) {
            response.setStatusCode(404);
            response.setStatusMessage("Limit '" + limitId + "' not found."); //$NON-NLS-1$ //$NON-NLS-2$
            response.end();
        }
    }

    /**
     * Implements the 'increment limit' REST endpoint.  Increments the limit and
     * returns the latest information (reset time, remaining limit, etc).  If the
     * limit has been exceeded, then a 409 is returned.  If the limit does not
     * already exist, a 404 is returned.
     * 
     * @param routingContext
     */
    private void incrementLimit(RoutingContext routingContext) {
        ZonedDateTime now = ZonedDateTime.now();

        String limitId = routingContext.request().getParam("limitId"); //$NON-NLS-1$
        System.out.println("Incrementing limit with id: " + limitId); //$NON-NLS-1$
        final LimitIncrementBean incLimit = Json.decodeValue(routingContext.getBodyAsString(), LimitIncrementBean.class);
        System.out.println("Incrementing by " + incLimit.getIncrementBy()); //$NON-NLS-1$

        final HttpServerResponse response = routingContext.response();
        try {
            LimitBean rval = limits.incrementLimit(now, limitId, incLimit.getIncrementBy());
            rval.setId(limitId);
            sendBeanAsResponse(rval, response);
        } catch (LimitExceededException e) {
            LimitExceededErrorBean rval = new LimitExceededErrorBean();
            rval.setResetOn(e.getResetOn());
            response.setStatusCode(429);
            sendBeanAsResponse(rval, response);
        } catch (LimitNotFoundException e) {
            response.setStatusCode(404);
            response.setStatusMessage("Limit '" + limitId + "' not found."); //$NON-NLS-1$ //$NON-NLS-2$
            response.end();
        }
    }

    /**
     * Implements the 'deletes a limit' REST endpoint.  This removes a limit from
     * the list of limits managed by the service.
     * 
     * @param routingContext
     */
    private void deleteLimit(RoutingContext routingContext) {
        ZonedDateTime now = ZonedDateTime.now();
        String limitId = routingContext.request().getParam("limitId"); //$NON-NLS-1$
        System.out.println("Deleting limit with id: " + limitId); //$NON-NLS-1$

        final HttpServerResponse response = routingContext.response();
        try {
            limits.deleteLimit(now, limitId);
            response.setStatusCode(204);
            response.end();
        } catch (LimitNotFoundException e) {
            response.setStatusCode(404);
            response.setStatusMessage("Limit '" + limitId + "' not found."); //$NON-NLS-1$ //$NON-NLS-2$
            response.end();
        }
    }

    /**
     * Send a simple java bean as the response to the REST request.  The bean will
     * be converted to JSON and sent.
     * @param bean
     * @param response
     */
    private static void sendBeanAsResponse(Object bean, HttpServerResponse response) {
        response.putHeader("Content-Type", "application/json"); //$NON-NLS-1$ //$NON-NLS-2$
        response.end(Json.encodePrettily(bean));
    }

    /**
     * Gets a request query paramter and converts it to the given data type.
     * @param request
     * @param paramName
     */
    private static <T> T getQueryParam(HttpServerRequest request, String paramName, Class<T> type, T defaultValue) {
        String query = request.query();
        Map<String, String> queryMap = parseQuery(query);
        String paramValue = queryMap.get(paramName);
        if (paramValue == null || paramValue.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return (T) type.getConstructor(String.class).newInstance(paramValue);
        } catch (InstantiationException | IllegalAccessException | IllegalArgumentException
                | InvocationTargetException | NoSuchMethodException | SecurityException e) {
            return defaultValue;
        }
    }

    /**
     * Parses a standard query string into a map of key-value pairs.  If a param has no
     * value it will be added to the map with a value of null.
     * @param query
     */
    protected static Map<String, String> parseQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.EMPTY_MAP;
        }
        Map<String, String> map = new HashMap<>();
        String [] split = query.split("&"); //$NON-NLS-1$
        for (String paramPair : split) {
            int idx = paramPair.indexOf('=');
            if (idx >= 0) {
                String key = paramPair.substring(0, idx);
                String value = paramPair.substring(idx + 1);
                map.put(key, value);
            } else {
                map.put(paramPair, null);
            }
        }
        return map;
    }

}
