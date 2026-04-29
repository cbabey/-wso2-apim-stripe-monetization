/*
 * Copyright (c) 2024, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.apim.monetization.webhook;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.wso2.apim.monetization.webhook.impl.CompleteSessionApiServiceImpl;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource for the browser-redirect Stripe Checkout completion endpoint.
 *
 * <p>Deployed at: {@code POST /api/am/stripe/complete-session?session_id={cs_xxx}}
 *
 * <p>The DevPortal UI calls this endpoint when the user lands back from Stripe
 * Checkout with a {@code ?session_id=cs_xxx} query parameter in the success URL.
 * It activates the pending APIM subscription immediately — before the concurrent
 * Stripe webhook fires.
 *
 * <p>Security: the Stripe Checkout Session ID ({@code cs_xxx}) has sufficient entropy
 * to serve as an unpredictable token. The executor-level idempotency guard prevents
 * duplicate activations if both paths arrive concurrently.
 */
@Path("/complete-session")
@Api(description = "Stripe Checkout Session browser-redirect completion API")
public class CompleteSessionApi {

    @Context
    MessageContext messageContext;

    private final CompleteSessionApiServiceImpl delegate = new CompleteSessionApiServiceImpl();

    /**
     * Activates the APIM subscription for the given Stripe Checkout Session.
     *
     * @param sessionId Stripe Checkout Session ID returned in the success URL ({@code cs_xxx})
     * @return {@code {"status":"completed"}} on success or an error response
     */
    @POST
    @Produces("application/json")
    @ApiOperation(
            value = "Complete a pending Stripe Checkout Session",
            notes = "Called by the DevPortal UI when the user returns from Stripe Checkout. "
                    + "Activates the APIM subscription by completing the pending workflow. "
                    + "Idempotent — safe to call from both webhook and browser-redirect paths concurrently.",
            response = Void.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Subscription activated (or already was active)"),
            @ApiResponse(code = 400, message = "Missing session_id query parameter"),
            @ApiResponse(code = 404, message = "Checkout session or workflow not found"),
            @ApiResponse(code = 500, message = "Internal server error during activation")
    })
    public Response completeSession(
            @ApiParam(value = "Stripe Checkout Session ID (cs_xxx)", required = true)
            @QueryParam("session_id") String sessionId) {

        return delegate.completeSession(sessionId, messageContext);
    }
}
