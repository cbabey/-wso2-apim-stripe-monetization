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
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.notifier.events.SubscriptionEvent;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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
 *       stripe plugin bundle, which creates the platform customer, shared customer,
 *       and Stripe subscription.</li>
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
     * Stripe event type fired when an invoice payment attempt fails.
     * Used to block the APIM subscription when a renewal charge cannot be collected.
     */
    private static final String EVENT_INVOICE_PAYMENT_FAILED = "invoice.payment_failed";

    /**
     * Stripe event type fired whenever a subscription's status changes
     * (e.g. past_due → active after a retry succeeds, or active → canceled).
     * Used to keep the APIM subscription status in sync with Stripe.
     */
    private static final String EVENT_SUBSCRIPTION_UPDATED = "customer.subscription.updated";

    /**
     * SQL to resolve a Stripe subscription ID to the APIM subscription UUID and tenant ID.
     * AM_MONETIZATION_SUBSCRIPTIONS stores the Stripe subscription ID alongside the
     * numeric API and application IDs, which are joined back to AM_SUBSCRIPTION for the UUID.
     */
    private static final String GET_APIM_SUBSCRIPTION_BY_STRIPE_SUB_ID =
            "SELECT s.UUID, ms.TENANT_ID " +
            "FROM AM_SUBSCRIPTION s " +
            "JOIN AM_MONETIZATION_SUBSCRIPTIONS ms " +
            "  ON ms.SUBSCRIBED_API_ID = s.API_ID " +
            "  AND ms.SUBSCRIBED_APPLICATION_ID = s.APPLICATION_ID " +
            "WHERE ms.SUBSCRIPTION_ID = ?";

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
        } else if (EVENT_INVOICE_PAYMENT_FAILED.equals(eventType)) {
            handleInvoicePaymentFailed(event);
        } else if (EVENT_SUBSCRIPTION_UPDATED.equals(eventType)) {
            handleSubscriptionUpdated(event);
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

    // -------------------------------------------------------------------------
    // Fix 2 — invoice.payment_failed
    // -------------------------------------------------------------------------

    /**
     * Handles {@code invoice.payment_failed} events.
     *
     * <p>Fired when a subscription renewal invoice cannot be collected (e.g. card
     * declined or insufficient funds). Blocks the APIM subscription and notifies
     * all Gateway instances via a {@code SUBSCRIPTIONS_UPDATE} event so API calls
     * are rejected immediately.
     */
    private void handleInvoicePaymentFailed(JsonNode event) {

        // Invoice object is at event.data.object; the subscription field holds the Stripe sub ID
        String stripeSubscriptionId = event.path("data").path("object")
                .path("subscription").asText(null);

        if (StringUtils.isBlank(stripeSubscriptionId)) {
            log.warn("Stripe webhook invoice.payment_failed: no subscription ID in event payload");
            return;
        }

        try {
            updateAPIMSubscriptionStatus(stripeSubscriptionId, APIConstants.SubscriptionStatus.BLOCKED);
            log.info("Stripe webhook invoice.payment_failed: blocked APIM subscription "
                    + "for Stripe subscription=" + stripeSubscriptionId);
        } catch (APIManagementException e) {
            log.error("Stripe webhook invoice.payment_failed: failed to block APIM subscription "
                    + "for Stripe subscription=" + stripeSubscriptionId, e);
        }
    }

    // -------------------------------------------------------------------------
    // Fix 3 — customer.subscription.updated
    // -------------------------------------------------------------------------

    /**
     * Handles {@code customer.subscription.updated} events.
     *
     * <p>Fired whenever a Stripe subscription transitions to a new status. Maps the
     * Stripe status to an APIM status and updates the subscription + notifies the Gateway:
     * <ul>
     *   <li>{@code active}              → {@code UNBLOCKED} (payment recovered)</li>
     *   <li>{@code past_due}            → {@code BLOCKED}   (renewal failed, retrying)</li>
     *   <li>{@code canceled}            → {@code BLOCKED}   (all retries exhausted)</li>
     *   <li>{@code incomplete_expired}  → {@code BLOCKED}   (initial period expired)</li>
     *   <li>All other statuses          → ignored</li>
     * </ul>
     */
    private void handleSubscriptionUpdated(JsonNode event) {

        JsonNode subNode = event.path("data").path("object");
        String stripeSubscriptionId = subNode.path("id").asText(null);
        String stripeStatus = subNode.path("status").asText(null);

        if (StringUtils.isBlank(stripeSubscriptionId) || StringUtils.isBlank(stripeStatus)) {
            log.warn("Stripe webhook customer.subscription.updated: missing id or status in event");
            return;
        }

        String newApimStatus = mapStripeStatusToAPIM(stripeStatus);
        if (newApimStatus == null) {
            if (log.isDebugEnabled()) {
                log.debug("Stripe webhook customer.subscription.updated: ignoring unmapped status="
                        + stripeStatus + " for subscription=" + stripeSubscriptionId);
            }
            return;
        }

        try {
            updateAPIMSubscriptionStatus(stripeSubscriptionId, newApimStatus);
            log.info("Stripe webhook customer.subscription.updated: set APIM subscription to "
                    + newApimStatus + " for Stripe subscription=" + stripeSubscriptionId
                    + " (Stripe status=" + stripeStatus + ")");
        } catch (APIManagementException e) {
            log.error("Stripe webhook customer.subscription.updated: failed to update APIM subscription "
                    + "for Stripe subscription=" + stripeSubscriptionId, e);
        }
    }

    /**
     * Maps a Stripe subscription status string to the corresponding APIM subscription status.
     *
     * @return the APIM status string, or {@code null} if the Stripe status should be ignored
     */
    private String mapStripeStatusToAPIM(String stripeStatus) {
        switch (stripeStatus) {
            case "active":
                return APIConstants.SubscriptionStatus.UNBLOCKED;
            case "past_due":
            case "canceled":
            case "incomplete_expired":
                return APIConstants.SubscriptionStatus.BLOCKED;
            default:
                return null; // trialing, paused, unpaid — not mapped
        }
    }

    // -------------------------------------------------------------------------
    // Shared helper — DB update + gateway event
    // -------------------------------------------------------------------------

    /**
     * Updates an APIM subscription status and notifies all Gateway instances.
     *
     * <p>Two operations are performed atomically from the caller's perspective:
     * <ol>
     *   <li>DB update via {@code ApiMgtDAO.updateSubscription(SubscribedAPI)} — persists
     *       the new status in {@code AM_SUBSCRIPTION}.</li>
     *   <li>Gateway notification via {@code APIUtil.sendNotification()} with a
     *       {@code SUBSCRIPTIONS_UPDATE} event — ensures every Gateway node enforces
     *       the new status in real time without a server restart.</li>
     * </ol>
     *
     * <p>This mirrors the logic inside {@code APIProviderImpl.updateSubscription()} but
     * without requiring an {@code APIProvider} instance (which needs tenant Carbon context).
     *
     * @param stripeSubscriptionId Stripe subscription ID (e.g. {@code sub_xxx})
     * @param newStatus            target APIM status ({@code BLOCKED} or {@code UNBLOCKED})
     */
    private void updateAPIMSubscriptionStatus(String stripeSubscriptionId, String newStatus)
            throws APIManagementException {

        // 1. Resolve Stripe subscription ID → APIM subscription UUID + tenantId
        String[] subInfo = lookupAPIMSubscription(stripeSubscriptionId);
        if (subInfo == null) {
            log.warn("updateAPIMSubscriptionStatus: no APIM subscription found for "
                    + "Stripe subscription=" + stripeSubscriptionId + " — may have been deleted");
            return;
        }
        String subscriptionUUID = subInfo[0];
        int tenantId = Integer.parseInt(subInfo[1]);

        // 2. Load full SubscribedAPI (needed for DB update and gateway event)
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        SubscribedAPI subscribedAPI = apiMgtDAO.getSubscriptionByUUID(subscriptionUUID);
        if (subscribedAPI == null) {
            log.warn("updateAPIMSubscriptionStatus: SubscribedAPI not found for UUID="
                    + subscriptionUUID);
            return;
        }

        // 3. Idempotency — skip if already at target status
        if (newStatus.equals(subscribedAPI.getSubStatus())) {
            if (log.isDebugEnabled()) {
                log.debug("updateAPIMSubscriptionStatus: subscription " + subscriptionUUID
                        + " already has status=" + newStatus + " — skipping");
            }
            return;
        }

        // 4. Persist new status in AM_SUBSCRIPTION
        subscribedAPI.setSubStatus(newStatus);
        apiMgtDAO.updateSubscription(subscribedAPI);

        // 5. Fire SUBSCRIPTIONS_UPDATE event so all Gateway nodes enforce immediately
        String tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        SubscriptionEvent subscriptionEvent = new SubscriptionEvent(
                APIConstants.EventType.SUBSCRIPTIONS_UPDATE.name(),
                subscribedAPI,
                tenantId,
                tenantDomain);
        APIUtil.sendNotification(subscriptionEvent, APIConstants.NotifierType.SUBSCRIPTIONS.name());
    }

    /**
     * Resolves a Stripe subscription ID to the APIM subscription UUID and tenant ID
     * by querying {@code AM_MONETIZATION_SUBSCRIPTIONS} joined to {@code AM_SUBSCRIPTION}.
     *
     * @return {@code String[]{uuid, tenantId}} or {@code null} if not found
     */
    private String[] lookupAPIMSubscription(String stripeSubscriptionId) {

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = APIMgtDBUtil.getConnection();
            ps = conn.prepareStatement(GET_APIM_SUBSCRIPTION_BY_STRIPE_SUB_ID);
            ps.setString(1, stripeSubscriptionId);
            rs = ps.executeQuery();
            if (rs.next()) {
                return new String[]{
                        rs.getString("UUID"),
                        String.valueOf(rs.getInt("TENANT_ID"))
                };
            }
        } catch (SQLException e) {
            log.error("DB error looking up APIM subscription for Stripe subscription="
                    + stripeSubscriptionId, e);
        } finally {
            APIMgtDBUtil.closeAllConnections(ps, conn, rs);
        }
        return null;
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
