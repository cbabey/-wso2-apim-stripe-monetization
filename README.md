# WSO2 APIM Stripe Monetization — Custom Implementation

This repository contains all components of the custom Stripe payment integration for WSO2 API Manager 4.5.0, enabling real payment method collection via Stripe Checkout during API subscription.

## Repository Structure

```
wso2-apim-stripe-monetization/
├── stripe-plugin/          OSGi bundle JAR — Stripe monetization workflow executor
├── stripe-webhook/         WAR — receives Stripe webhook events and resumes APIM workflows
├── devportal-ui/           DevPortal React UI overrides for subscription redirect handling
│   └── override/src/app/components/
│       ├── Applications/Details/Subscriptions.jsx
│       └── Apis/Details/Credentials/Credentials.jsx
└── docs/
    └── STRIPE_MONETIZATION_CUSTOMIZATION.md   Full architecture and implementation guide
```

## Components

### `stripe-plugin/`
A Maven OSGi bundle that extends `StripeSubscriptionCreationWorkflowExecutor`. Deployed to `<APIM_HOME>/repository/components/dropins/`.

Key customisations:
- Creates a Stripe Checkout Session (setup mode) for new subscribers instead of using the legacy `tok_visa` test token
- Returns `HttpWorkflowResponse` with the Checkout URL so APIM propagates it as `redirectionParams`
- On webhook-triggered `complete()`, retrieves the real payment method from the completed session and creates platform customer, shared customer, and subscription

### `stripe-webhook/`
A standalone WAR deployed at `/api/am/stripe/` on the Carbon/Tomcat server.

- Exposes `POST /api/am/stripe/webhook` to receive `checkout.session.completed` events from Stripe
- Verifies Stripe HMAC-SHA256 signature natively (no stripe-java dependency — avoids Gson classloader conflict with Carbon OSGi runtime)
- Resumes the pending APIM subscription workflow by calling `WorkflowExecutorFactory.complete()`

### `devportal-ui/`
DevPortal override files (drop into `devportal/override/src/`) — active at runtime without a rebuild.

- Reads `redirectionParams` from the `POST /subscriptions` response
- Redirects the subscriber's browser to the Stripe Checkout URL when `status: ON_HOLD`

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
# Plugin
cp stripe-plugin/target/org.wso2.apim.monetization.impl-*.jar \
   <APIM_HOME>/repository/components/dropins/

# Webhook WAR
cp stripe-webhook/target/api#am#stripe.war \
   <APIM_HOME>/repository/deployment/server/webapps/

# DevPortal UI overrides
cp -r devportal-ui/override/ \
   <APIM_HOME>/repository/deployment/server/webapps/devportal/
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
<Executor name="org.wso2.apim.monetization.impl.workflow.StripeSubscriptionCreationWorkflowExecutor">
    <Property name="checkoutSuccessUrl">https://<devportal-host>:9443/devportal/subscription/success</Property>
    <Property name="checkoutCancelUrl">https://<devportal-host>:9443/devportal/subscription/cancel</Property>
</Executor>
```

**Stripe Dashboard** — register webhook endpoint:
- URL: `https://<apim-host>:9443/api/am/stripe/webhook`
- Event: `checkout.session.completed`

### 4. Restart APIM

## Documentation

See [`docs/STRIPE_MONETIZATION_CUSTOMIZATION.md`](docs/STRIPE_MONETIZATION_CUSTOMIZATION.md) for full architecture diagrams, flow descriptions, and issue resolution history.

## Branching Strategy

| Branch | Purpose |
|---|---|
| `main` | Stable, deployed implementation |
| `feature/*` | New features or enhancements |
| `fix/*` | Bug fixes |
