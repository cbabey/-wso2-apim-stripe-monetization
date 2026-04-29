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

package org.wso2.apim.monetization.webhook.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.cxf.jaxrs.ext.MessageContext;
import org.wso2.apim.monetization.webhook.WebhookApiService;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutorFactory;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Handles inbound Stripe webhook events.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Verify the {@code Stripe-Signature} header with HMAC-SHA256 using the configured
 *       webhook secret. The verification is implemented natively (no stripe-java dependency)
 *       to avoid the Gson classloader conflict with the Carbon OSGi runtime.</li>
 *   <li>Parse the event JSON with Jackson to extract the event type and session data.</li>
 *   <li>For {@code checkout.session.completed}: extract the APIM {@code workflowReference}
 *       stored in the session metadata.</li>
 *   <li>Load the pending {@code WorkflowDTO} from the APIM database.</li>
 *   <li>Pass the Stripe session ID via {@code workflowDTO.attributes} and call
 *       {@code WorkflowExecutor.complete()} — at runtime this dispatches to
 *       {@code StripeSubscriptionCreationWorkflowExecutor.complete()} from the
 *       stripe plugin bundle, which retrieves the Stripe-created subscription from
 *       the connected account, persists it, and activates the APIM subscription.</li>
 *   <li>Return HTTP 200 so Stripe does not retry.</li>
 * </ol>
 *
 * <h3>Webhook secret configuration</h3>
 * Add the following to {@code <APIM_HOME>/repository/conf/api-manager.xml}:
 * <pre>{@code
 * <Monetization>
 *     <StripeWebhookSecret>whsec_your_signing_secret_here</StripeWebhookSecret>
 * </Monetization>
 * }</pre>
 */
public class WebhookApiServiceImpl implements WebhookApiService {

    private static final Log log = LogFactory.getLog(WebhookApiServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * api-manager.xml property key for the Stripe endpoint signing secret.
     * Value starts with {@code whsec_}.
     */
    private static final String STRIPE_WEBHOOK_SECRET_PROP = "Monetization.StripeWebhookSecret";

    /**
     * Stripe event type fired when a Checkout Session is completed by the customer.
     */
    private static final String EVENT_CHECKOUT_SESSION_COMPLETED = "checkout.session.completed";

    /**
     * Key used in Stripe session metadata to carry the APIM workflow internal reference
     * (subscription ID). Set by StripeSubscriptionCreationWorkflowExecutor when it creates
     * the Checkout Session.
     */
    private static final String METADATA_WORKFLOW_REF = "workflowReference";

    /**
     * Key passed via WorkflowDTO.attributes to StripeSubscriptionCreationWorkflowExecutor.complete()
     * so it knows to run the Checkout-based customer + subscription creation path.
     */
    static final String ATTR_CHECKOUT_SESSION_ID = "checkoutSessionId";

    /**
     * Maximum allowed difference (in seconds) between the Stripe-Signature timestamp and
     * the server clock. Stripe recommends 300 s (5 minutes) to prevent replay attacks.
     */
    private static final long STRIPE_TIMESTAMP_TOLERANCE_SECONDS = 300L;

    // -------------------------------------------------------------------------

    @Override
    public Response stripeWebhookPost(String payload, String stripeSignature,
            MessageContext messageContext) {

        // 1. Reject immediately if Stripe-Signature header is missing
        if (StringUtils.isBlank(stripeSignature)) {
            log.warn("Stripe webhook received without Stripe-Signature header — rejected");
            return jsonResponse(Response.Status.BAD_REQUEST, "Missing Stripe-Signature header");
        }

        // 2. Read webhook secret from api-manager.xml
        String webhookSecret = getWebhookSecret();
        if (StringUtils.isBlank(webhookSecret)) {
            log.error("Stripe webhook secret is not configured. "
                    + "Set 'Monetization.StripeWebhookSecret' in api-manager.xml");
            return jsonResponse(Response.Status.INTERNAL_SERVER_ERROR,
                    "Webhook secret not configured on the server");
        }

        // 3. Verify HMAC-SHA256 signature natively (no stripe-java required)
        if (!verifyStripeSignature(payload, stripeSignature, webhookSecret)) {
            log.warn("Stripe webhook signature verification failed");
            return jsonResponse(Response.Status.BAD_REQUEST, "Invalid Stripe signature");
        }

        // 4. Parse event JSON
        JsonNode event;
        try {
            event = MAPPER.readTree(payload);
        } catch (Exception e) {
            log.error("Failed to parse Stripe webhook JSON payload", e);
            return jsonResponse(Response.Status.BAD_REQUEST, "Invalid JSON payload");
        }

        String eventType = event.path("type").asText("");
        String eventId   = event.path("id").asText("");

        if (log.isDebugEnabled()) {
            log.debug("Stripe webhook received: type=" + eventType + " id=" + eventId);
        }

        // 5. Route by event type
        if (EVENT_CHECKOUT_SESSION_COMPLETED.equals(eventType)) {
            handleCheckoutSessionCompleted(event);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Stripe webhook: ignoring unhandled event type: " + eventType);
            }
        }

        // Always return 200 so Stripe does not retry unhandled event types
        return Response.ok("{\"received\":true}").build();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Verifies a Stripe webhook signature using HMAC-SHA256.
     *
     * <p>Stripe-Signature header format:
     * {@code t=<unix-timestamp>,v1=<hex-signature>[,v1=<hex-signature2>...]}
     *
     * <p>The signed payload is: {@code <timestamp>.<rawPayload>}
     *
     * @param payload         raw request body (UTF-8 string)
     * @param stripeSignature value of the {@code Stripe-Signature} HTTP header
     * @param webhookSecret   endpoint signing secret from the Stripe dashboard
     * @return {@code true} if the signature is valid and the timestamp is within tolerance
     */
    private boolean verifyStripeSignature(String payload, String stripeSignature, String webhookSecret) {
        String timestamp  = null;
        String expectedSig = null;

        for (String part : stripeSignature.split(",")) {
            part = part.trim();
            if (part.startsWith("t=")) {
                timestamp = part.substring(2);
            } else if (part.startsWith("v1=") && expectedSig == null) {
                // Use the first v1 signature found in the header
                expectedSig = part.substring(3);
            }
        }

        if (timestamp == null || expectedSig == null) {
            log.warn("Stripe-Signature header is malformed: " + stripeSignature);
            return false;
        }

        // Enforce timestamp tolerance to prevent replay attacks
        long eventTime;
        try {
            eventTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            log.warn("Invalid timestamp in Stripe-Signature header: " + timestamp);
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        if (Math.abs(now - eventTime) > STRIPE_TIMESTAMP_TOLERANCE_SECONDS) {
            log.warn("Stripe webhook timestamp outside tolerance window. "
                    + "Event time: " + eventTime + ", Server time: " + now);
            return false;
        }

        // Compute HMAC-SHA256("<timestamp>.<rawPayload>", webhookSecret)
        String signedPayload = timestamp + "." + payload;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8));
            String computedSig = bytesToHex(hash);
            return computedSig.equals(expectedSig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Failed to compute HMAC-SHA256 for Stripe signature verification", e);
            return false;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Processes a {@code checkout.session.completed} event.
     * Errors are logged but NOT propagated — we still return 200 to Stripe
     * to prevent retries for non-transient failures.
     */
    private void handleCheckoutSessionCompleted(JsonNode event) {

        // Navigate: event.data.object = the Checkout Session object
        JsonNode sessionNode = event.path("data").path("object");
        String sessionId = sessionNode.path("id").asText(null);

        // Extract APIM workflow reference from session metadata
        String workflowReference = sessionNode
                .path("metadata").path(METADATA_WORKFLOW_REF).asText(null);

        if (StringUtils.isBlank(workflowReference)) {
            log.error("Stripe webhook: workflowReference not found in session metadata "
                    + "for session: " + sessionId);
            return;
        }

        try {
            ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();

            // Load the pending WorkflowDTO from APIM DB
            WorkflowDTO workflowDTO = apiMgtDAO.retrieveWorkflowFromInternalReference(
                    workflowReference,
                    WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);

            if (workflowDTO == null) {
                log.error("Stripe webhook: no pending workflow found for workflowReference: "
                        + workflowReference + " (session: " + sessionId + ")");
                return;
            }

            // Pass the Stripe session ID to complete() via the transient attributes map.
            // StripeSubscriptionCreationWorkflowExecutor.complete() reads this key to
            // distinguish a Checkout-triggered completion from a manual admin approval.
            workflowDTO.getAttributes().put(ATTR_CHECKOUT_SESSION_ID, sessionId);

            // Set workflow status to APPROVED so complete() unblocks the subscription
            workflowDTO.setStatus(WorkflowStatus.APPROVED);
            workflowDTO.setUpdatedTime(System.currentTimeMillis());

            // Obtain the executor configured for subscription creation workflows.
            // At runtime this resolves to StripeSubscriptionCreationWorkflowExecutor
            // from the stripe plugin bundle (loaded via workflow-extensions.xml).
            WorkflowExecutor executor = WorkflowExecutorFactory.getInstance()
                    .getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);

            executor.complete(workflowDTO);

            log.info("Stripe webhook: subscription workflow completed successfully "
                    + "for workflowReference=" + workflowReference
                    + " session=" + sessionId);

        } catch (APIManagementException e) {
            log.error("Stripe webhook: APIM error completing workflow for workflowReference="
                    + workflowReference, e);
        } catch (WorkflowException e) {
            log.error("Stripe webhook: workflow execution error for workflowReference="
                    + workflowReference, e);
        }
    }

    /**
     * Reads the Stripe webhook endpoint signing secret from {@code api-manager.xml}.
     * Returns {@code null} if not configured.
     */
    private String getWebhookSecret() {
        try {
            APIManagerConfiguration config = ServiceReferenceHolder.getInstance()
                    .getAPIManagerConfigurationService()
                    .getAPIManagerConfiguration();
            return config.getFirstProperty(STRIPE_WEBHOOK_SECRET_PROP);
        } catch (Exception e) {
            log.error("Failed to read Stripe webhook secret from configuration", e);
            return null;
        }
    }

    /**
     * Builds a simple JSON error/status response.
     */
    private Response jsonResponse(Response.Status status, String message) {
        String body = "{\"error\":\"" + message + "\"}";
        return Response.status(status).entity(body).build();
    }
}
