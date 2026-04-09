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

import org.apache.cxf.jaxrs.ext.MessageContext;

import javax.ws.rs.core.Response;

/**
 * Service interface for the Stripe webhook endpoint.
 * Implemented by {@link org.wso2.apim.monetization.webhook.impl.WebhookApiServiceImpl}.
 */
public interface WebhookApiService {

    /**
     * Handles an inbound Stripe webhook event.
     *
     * @param payload         raw JSON body of the Stripe event (must not be pre-parsed)
     * @param stripeSignature value of the {@code Stripe-Signature} header
     * @param messageContext  CXF message context
     * @return HTTP 200 on success; 400 on signature failure; 500 on internal error
     */
    Response stripeWebhookPost(String payload, String stripeSignature, MessageContext messageContext);
}
