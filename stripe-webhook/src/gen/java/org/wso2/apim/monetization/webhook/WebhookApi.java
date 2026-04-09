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
import org.wso2.apim.monetization.webhook.impl.WebhookApiServiceImpl;

import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource class for the Stripe webhook endpoint.
 *
 * <p>Deployed at: {@code POST /api/am/stripe/webhook}
 *
 * <p>This endpoint is intentionally unauthenticated at the OAuth2 level.
 * Request authenticity is verified via the {@code Stripe-Signature} HMAC header
 * inside {@link WebhookApiServiceImpl}.
 */
@Path("/webhook")
@Api(description = "Stripe Payment Webhook API")
public class WebhookApi {

    @Context
    MessageContext messageContext;

    WebhookApiService delegate = new WebhookApiServiceImpl();

    /**
     * Receives a Stripe event and processes it.
     *
     * <p>Stripe sends the raw JSON body with a {@code Stripe-Signature} header that
     * contains an HMAC signature. The raw body MUST NOT be pre-parsed (JSON parsing
     * invalidates the signature check). We declare the parameter as {@code String}
     * so CXF reads it as-is.
     */
    @POST
    @Consumes("application/json")
    @Produces("application/json")
    @ApiOperation(
            value = "Receive a Stripe webhook event",
            notes = "Handles checkout.session.completed events to resume pending APIM subscription workflows. "
                    + "Authentication is via Stripe-Signature HMAC — no OAuth2 token required.",
            response = Void.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Event received and processed successfully"),
            @ApiResponse(code = 400, message = "Invalid or missing Stripe-Signature header"),
            @ApiResponse(code = 500, message = "Internal server error while processing the event")
    })
    public Response stripeWebhookPost(
            @ApiParam(value = "Raw Stripe event JSON payload", required = true)
                    String payload,
            @ApiParam(value = "Stripe HMAC signature header", required = true)
            @HeaderParam("Stripe-Signature")
                    String stripeSignature) {

        return delegate.stripeWebhookPost(payload, stripeSignature, messageContext);
    }
}
