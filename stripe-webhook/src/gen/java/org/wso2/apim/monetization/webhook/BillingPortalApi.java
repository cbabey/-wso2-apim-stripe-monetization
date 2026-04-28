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
import org.wso2.apim.monetization.webhook.impl.BillingPortalApiServiceImpl;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * JAX-RS resource that generates a Stripe Customer Portal session URL for a Dev Portal application.
 *
 * <p>Deployed at: {@code GET /api/am/stripe/billing-portal?applicationId={uuid}&returnUrl={url}}
 *
 * <p>The Dev Portal UI calls this endpoint when the user clicks "Manage Billing" on a
 * subscription row. The returned URL is a short-lived Stripe-hosted page where the customer
 * can update their payment method, view invoices, and manage subscriptions.
 *
 * <p>After the user finishes in the portal, Stripe redirects them to {@code returnUrl}
 * (typically the Dev Portal subscriptions page they came from).
 */
@Path("/billing-portal")
@Api(description = "Stripe Customer Portal session endpoint")
public class BillingPortalApi {

    @Context
    MessageContext messageContext;

    private final BillingPortalApiServiceImpl delegate = new BillingPortalApiServiceImpl();

    /**
     * Creates a Stripe Customer Portal session and returns the session URL.
     *
     * @param applicationId Dev Portal application UUID
     * @param returnUrl     URL to redirect back to after the portal session ends
     * @return {@code {"url":"https://billing.stripe.com/session/..."}} or error JSON
     */
    @GET
    @Produces("application/json")
    @ApiOperation(
            value = "Get Stripe Customer Portal URL for an application",
            notes = "Creates a short-lived Stripe Customer Portal session for the subscriber "
                    + "who owns the application. The portal lets the user update payment methods, "
                    + "view invoices, and manage subscriptions. After finishing, Stripe redirects "
                    + "the user to returnUrl.",
            response = Void.class
    )
    @ApiResponses(value = {
            @ApiResponse(code = 200, message = "Portal URL returned — redirect the browser to url"),
            @ApiResponse(code = 400, message = "Missing applicationId parameter"),
            @ApiResponse(code = 404, message = "No Stripe billing account found for this application"),
            @ApiResponse(code = 500, message = "Internal server error")
    })
    public Response getBillingPortalUrl(
            @ApiParam(value = "Dev Portal application UUID", required = true)
            @QueryParam("applicationId") String applicationId,
            @ApiParam(value = "URL to return to after the portal session")
            @QueryParam("returnUrl") String returnUrl) {

        return delegate.getBillingPortalUrl(applicationId, returnUrl, messageContext);
    }
}
