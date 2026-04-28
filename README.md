# WSO2 APIM Stripe Monetization — Custom Implementation

This repository contains all components of the custom Stripe payment integration for WSO2 API Manager 4.5.0, enabling real payment method collection via Stripe Checkout during API subscription, payment failure enforcement, and a Stripe Customer Portal for subscribers.

## Repository Structure

```
wso2-apim-stripe-monetization/
├── stripe-plugin/          OSGi bundle JAR — Stripe monetization workflow executor
├── stripe-webhook/         WAR — receives Stripe webhook events and resumes APIM workflows
├── devportal-ui/           DevPortal React UI overrides for subscription redirect handling
│   └── override/src/app/components/
│       ├── Applications/Details/Subscriptions.jsx
│       ├── Applications/Details/SubscriptionTableData.jsx
│       └── Apis/Details/Credentials/Credentials.jsx
└── docs/
    └── STRIPE_MONETIZATION_CUSTOMIZATION.md   Full architecture and implementation guide
```

## Components

### `stripe-plugin/`
A Maven OSGi bundle that extends `StripeSubscriptionCreationWorkflowExecutor`. Deployed to `<APIM_HOME>/repository/components/lib/`.

Key customisations:
- Creates a Stripe Checkout Session (setup mode) for new subscribers instead of using the legacy `tok_visa` test token
- Returns `HttpWorkflowResponse` with the Checkout URL so APIM propagates it as `redirectionParams`
- On webhook-triggered or browser-redirect `complete()`, retrieves the real payment method from the completed session and creates platform customer, shared customer, and subscription
- Handles `incomplete` subscription status (`STRIPE_PAYMENT_DECLINED` marker pattern): saves sub ID for Fix 3, marks session COMPLETED
- Retries with platform customer's current default PM on stale-shared-customer failures before falling back to Checkout
- Platform customer creation is idempotent (DB-first check + Stripe recovery on "already attached" error)

### `stripe-webhook/`
A standalone WAR deployed at `/api/am/stripe/` on the Carbon/Tomcat server. Exposes four endpoints:

| Endpoint | Method | Purpose |
|---|---|---|
| `POST /api/am/stripe/webhook` | Receives Stripe events; verifies HMAC-SHA256 signature natively |
| `POST /api/am/stripe/complete-session` | Browser-redirect completion — called by DevPortal UI with `?session_id=cs_xxx` |
| `GET /api/am/stripe/checkout-url` | Returns checkout URL + session ID for a pending ON_HOLD subscription |
| `GET /api/am/stripe/billing-portal` | Opens Stripe Customer Portal for a given application UUID |

Registered Stripe webhook events:
- `checkout.session.completed` — activates new subscription
- `invoice.payment_failed` — blocks API access when recurring payment fails
- `customer.subscription.updated` — unblocks API access when Stripe auto-retry succeeds

### `devportal-ui/`
DevPortal override files (drop into `devportal/override/src/`) — active at runtime without a rebuild.

- Reads `redirectionParams` from the `POST /subscriptions` response and redirects the browser to Stripe Checkout when `status: ON_HOLD`
- On return from Stripe (`?session_id=cs_xxx`), shows a loading UI and calls `/complete-session` to activate the subscription
- Handles HTTP 402 (payment declined) with a descriptive alert — does not fall back to the consumed Checkout URL
- Shows a **Manage Billing** button on active monetized subscriptions that opens the Stripe Customer Portal

## Quick Start

### 1. Build

```bash
# Build the plugin JAR
cd stripe-plugin && mvn clean package

# Build the webhook WAR
cd ../stripe-webhook && mvn clean package
```

### 2. Deploy

```bash
# Plugin — lib/ only (not dropins/)
cp stripe-plugin/target/org.wso2.apim.monetization.impl-*.jar \
   <APIM_HOME>/repository/components/lib/

# Webhook WAR — delete extracted dir first if server was running
rm -rf <APIM_HOME>/repository/deployment/server/webapps/api#am#stripe/
cp stripe-webhook/target/api#am#stripe.war \
   <APIM_HOME>/repository/deployment/server/webapps/

# DevPortal UI overrides
cp devportal-ui/override/src/app/components/Applications/Details/Subscriptions.jsx \
   <APIM_HOME>/repository/deployment/server/webapps/devportal/override/src/app/components/Applications/Details/
cp devportal-ui/override/src/app/components/Applications/Details/SubscriptionTableData.jsx \
   <APIM_HOME>/repository/deployment/server/webapps/devportal/override/src/app/components/Applications/Details/
```

### 3. Configure

**`<APIM_HOME>/repository/conf/api-manager.xml`**
```xml
<Monetization>
    <StripeWebhookSecret>whsec_your_signing_secret_here</StripeWebhookSecret>
</Monetization>
```

**`<APIM_HOME>/repository/resources/workflow-extensions.xml`**
```xml
<SubscriptionCreation executor="org.wso2.apim.monetization.impl.workflow.StripeSubscriptionCreationWorkflowExecutor">
    <Property name="checkoutSuccessUrl">https://&lt;devportal-host&gt;:9443/devportal/applications</Property>
    <Property name="checkoutCancelUrl">https://&lt;devportal-host&gt;:9443/devportal/applications</Property>
</SubscriptionCreation>
```

> **Note:** Do NOT add `/subscriptions` to `checkoutSuccessUrl` — the plugin appends `/{appUUID}/subscriptions` automatically.

**Stripe Dashboard** — register webhook endpoint:
- URL: `https://<apim-host>:9443/api/am/stripe/webhook`
- Events: `checkout.session.completed`, `invoice.payment_failed`, `customer.subscription.updated`

### 4. Restart APIM

## Documentation

See [`docs/STRIPE_MONETIZATION_CUSTOMIZATION.md`](docs/STRIPE_MONETIZATION_CUSTOMIZATION.md) for full architecture diagrams, sequence diagrams, flow descriptions, and issue resolution history.

## Branching Strategy

| Branch | Purpose |
|---|---|
| `main` | Stable, deployed implementation |
| `feature/*` | New features or enhancements |
| `fix/*` | Bug fixes |
