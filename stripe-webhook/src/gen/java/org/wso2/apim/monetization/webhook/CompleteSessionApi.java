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
 * JAX-RS resource for the browser-redirect Stripe subscription completion endpoint.
 *
 * <p>Deployed at: {@code POST /api/am/stripe/complete-session?session_id={cs_xxx}}
 *
 * <p>After the subscriber completes card entry on Stripe Checkout, Stripe redirects them
 * to the configured {@code checkoutSuccessUrl} with {@code ?session_id=cs_xxx} appended.
 * The DevPortal UI reads this query parameter on page load and calls this endpoint to
 * trigger workflow completion — in parallel with the Stripe webhook path.
 *
 * <p>An atomic DB claim (PENDING → IN_PROGRESS) inside the workflow executor ensures
 * that whichever path (this endpoint or the webhook) arrives first wins, and the other
 * safely skips the Stripe API calls.
 */
@Path("/complete-session")
@Api(description = "Stripe browser-redirect subscription completion endpoint")
public class CompleteSessionApi {

    @Context
    MessageContext messageContext;

    private final CompleteSessionApiServiceImpl delegate = new CompleteSessionApiServiceImpl();

    /**
     * Completes an APIM subscription workflow after Stripe Checkout session is finalised.
     *
     * @param sessionId Stripe Checkout session ID (cs_xxxx), passed as a query parameter
     * @return 200 on success (or already completed); 400 on bad/missing session ID; 500 on error
     */
    @POST
    @Produces("application/json")
    @ApiOperation(
            value = "Complete subscription workflow after Stripe Checkout",
            notes = "Called by the DevPortal UI when the subscriber returns from Stripe Checkout "
                    + "with ?session_id=cs_xxx. Uses a first-one-wins DB claim so this path and "
                    + "the Stripe webhook path can run concurrently without double-processing.",
            response = Void.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Subscription activated (or already active)"),
            @ApiResponse(code = 400, message = "Missing or invalid session_id parameter"),
            @ApiResponse(code = 404, message = "Checkout session not found"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public Response completeSession(
            @ApiParam(value = "Stripe Checkout session ID (cs_xxxx)", required = true)
            @QueryParam("session_id") String sessionId) {

        return delegate.completeSession(sessionId, messageContext);
    }
}
