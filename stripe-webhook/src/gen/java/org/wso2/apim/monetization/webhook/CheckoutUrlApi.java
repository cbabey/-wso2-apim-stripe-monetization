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
import org.wso2.apim.monetization.webhook.impl.CheckoutUrlApiServiceImpl;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource that exposes the Stripe Checkout URL for a pending subscription.
 *
 * <p>Deployed at: {@code GET /api/am/stripe/checkout-url?subscriptionId={id}}
 *
 * <p>The DevPortal UI calls this endpoint immediately after a subscription creation
 * returns {@code ON_HOLD} to obtain the Stripe-hosted payment URL and redirect the user.
 */
@Path("/checkout-url")
@Api(description = "Stripe Checkout URL lookup API")
public class CheckoutUrlApi {

    @Context
    MessageContext messageContext;

    private final CheckoutUrlApiServiceImpl delegate = new CheckoutUrlApiServiceImpl();

    /**
     * Returns the Stripe Checkout URL for a pending subscription workflow.
     *
     * @param subscriptionId APIM internal subscription ID (workflow reference)
     * @return {@code {"checkoutUrl":"https://checkout.stripe.com/..."}} or 404
     */
    @GET
    @Produces("application/json")
    @ApiOperation(
            value = "Get the Stripe Checkout URL for a pending subscription",
            notes = "Called by the DevPortal UI after subscription creation returns ON_HOLD. "
                    + "Returns the Stripe-hosted payment page URL so the user can complete payment.",
            response = Void.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Checkout URL returned successfully"),
            @ApiResponse(code = 400, message = "Missing subscriptionId parameter"),
            @ApiResponse(code = 404, message = "No pending Stripe checkout session for this subscription"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public Response getCheckoutUrl(
            @ApiParam(value = "APIM internal subscription ID", required = true)
            @QueryParam("subscriptionId") String subscriptionId) {

        return delegate.getCheckoutUrl(subscriptionId, messageContext);
    }
}
