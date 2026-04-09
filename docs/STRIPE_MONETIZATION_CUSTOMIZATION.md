# WSO2 APIM Stripe Monetization — Customization Documentation

## Overview

This document describes:
1. How the **default** WSO2 APIM Stripe monetization subscription workflow operates out-of-the-box.
2. The **custom enhancements** implemented to support real payment method collection via Stripe Checkout, browser redirection, webhook-driven workflow resumption, and multi-application subscriber support.

---

## Part 1 — Default WSO2 APIM Stripe Subscription Flow

### 1.1 Architecture Components

| Component | Role |
|---|---|
| **WSO2 DevPortal** | Frontend where subscribers discover and subscribe to APIs |
| **APIM Store REST API** | Backend REST endpoint (`POST /subscriptions`) that processes subscription requests |
| **`StripeSubscriptionCreationWorkflowExecutor`** | OSGi bundle (dropins JAR) that handles the Stripe-specific logic during subscription creation |
| **Stripe Platform Account** | The API Marketplace owner's Stripe account — holds subscriber (platform) customers |
| **Stripe Connected Account** | Each API Provider's Stripe account — holds shared customers and subscriptions |

### 1.2 Stripe Customer Model

WSO2's Stripe integration uses a **platform / connected account split**. Think of it like a shopping mall:

- The **mall management** (WSO2 APIM admin) owns the building and issues a loyalty card to every shopper.
- Each **shop inside the mall** (API Provider) has its own Stripe account and needs to know who their paying customers are.
- A **shopper** (API Subscriber) gets one loyalty card from the mall, but each shop they visit creates their own record of that customer so they can charge them independently.

This translates to three Stripe objects:

| Stripe Object | Belongs To | Created When | Purpose |
|---|---|---|---|
| **Platform Customer** | Marketplace owner's Stripe account | First time subscriber ever subscribes to **any** API | Single global identity for the subscriber — payment method lives here |
| **Shared Customer** | API Provider's connected Stripe account | Each time subscriber uses a **new application** with a **new provider** | Local copy of the subscriber on that provider's account — needed so the provider can bill them |
| **Subscription** | API Provider's connected Stripe account | Each API subscription | Links the shared customer to a specific API tier's billing plan, and triggers recurring charges |

---

#### Concrete Example

Suppose the API marketplace has two providers:
- **WeatherCo** — publishes `Weather API`
- **MapsCo** — publishes `Maps API`

And a subscriber **Alice** who has two applications: **App-1** and **App-2**.

Here is what gets created in Stripe as Alice subscribes to different APIs:

| Alice's Action | Platform Customer | Shared Customer Created | Subscription Created |
|---|---|---|---|
| Subscribes to Weather API with App-1 | `cus_platform_alice` *(created now)* | `cus_shared_alice_weatherco_app1` on WeatherCo account | `sub_weatherco_app1_gold` |
| Subscribes to Maps API with App-1 | `cus_platform_alice` *(reused)* | `cus_shared_alice_mapsco_app1` on MapsCo account | `sub_mapsco_app1_silver` |
| Subscribes to Weather API with App-2 | `cus_platform_alice` *(reused)* | `cus_shared_alice_weatherco_app2` on WeatherCo account *(new — different app)* | `sub_weatherco_app2_basic` |

**Key rules:**
- There is always exactly **1 platform customer per subscriber** (across the whole marketplace).
- There is **1 shared customer per (subscriber application, API provider) pair** — the same application connecting to two different providers creates two separate shared customers.
- There is **1 subscription per (shared customer, API tier)** pair — subscribing to a different tier of the same API creates a new subscription.

---

#### Why the Split?

Stripe's connected account model requires that subscriptions and charges be created **on the connected account** (the provider), not on the platform. However, the payment method (the card) is stored on the **platform account** because the subscriber registered there. The shared customer is a "clone" that lets the provider charge through the platform's stored card without the subscriber needing to re-enter their card for every provider.

```mermaid
graph TB
    subgraph "Stripe Platform Account (Marketplace Owner)"
        PC["Platform Customer<br/>cus_platform_alice<br/>💳 card on file"]
    end

    subgraph "WeatherCo Connected Account"
        SC1["Shared Customer<br/>cus_shared_alice_weatherco_app1<br/>(cloned from platform)"]
        SC2["Shared Customer<br/>cus_shared_alice_weatherco_app2<br/>(cloned from platform)"]
        SUB1["Subscription<br/>Weather API / Gold / App-1<br/>$20/month"]
        SUB2["Subscription<br/>Weather API / Basic / App-2<br/>$5/month"]
    end

    subgraph "MapsCo Connected Account"
        SC3["Shared Customer<br/>cus_shared_alice_mapsco_app1<br/>(cloned from platform)"]
        SUB3["Subscription<br/>Maps API / Silver / App-1<br/>$10/month"]
    end

    Alice["Alice (APIM Subscriber)"] -->|"1 global card"| PC
    PC -->|"cloned →"| SC1
    PC -->|"cloned →"| SC2
    PC -->|"cloned →"| SC3
    SC1 --> SUB1
    SC2 --> SUB2
    SC3 --> SUB3
```

### 1.3 Default Subscription Flow

In the default implementation, when a user subscribes to a monetized API, the workflow executor performs all Stripe operations **synchronously** and **immediately** marks the subscription as `UNBLOCKED` (active).

The critical detail: the default implementation creates the platform customer using a **Stripe test token `tok_visa`** hardcoded in `StripeMonetizationConstants.DEFAULT_TOKEN`. This is a Stripe test card token intended for test environments — it does not collect any real payment information from the subscriber.

```mermaid
sequenceDiagram
    participant U as User (Browser)
    participant DP as WSO2 DevPortal
    participant API as APIM Store API
    participant WF as StripeSubscriptionWorkflowExecutor
    participant PA as Stripe Platform Account
    participant CA as Stripe Connected Account<br/>(API Provider)

    U->>DP: Click Subscribe to API
    DP->>API: POST /subscriptions<br/>{apiId, applicationId, tier}
    API->>WF: monetizeSubscription(workflowDTO, api)

    alt Platform Customer does NOT exist
        WF->>PA: Customer.create(tok_visa)
        PA-->>WF: platform_customer_id
        WF->>WF: Save to AM_STRIPE_PLATFORM_CUSTOMER (DB)
    end

    alt Shared Customer does NOT exist
        WF->>CA: Token.create(platform_customer_id)
        CA-->>WF: clone_token_id
        WF->>CA: Customer.create(clone_token_id)
        CA-->>WF: shared_customer_id
        WF->>WF: Save to AM_STRIPE_SHARED_CUSTOMER (DB)
    end

    WF->>CA: Subscription.create(shared_customer_id, plan_id)
    CA-->>WF: subscription_id
    WF->>WF: Save to AM_STRIPE_SUBSCRIPTION (DB)

    WF-->>API: WorkflowStatus.APPROVED
    API-->>DP: {"status": "UNBLOCKED"}
    DP-->>U: Subscription Active
```

### 1.4 Limitations of the Default Implementation

| Limitation | Impact |
|---|---|
| **Uses `tok_visa` test token** | No real payment information is collected from the subscriber. Subscriptions are approved without any card on file. |
| **Synchronous — no user interaction** | The subscriber is never asked for their payment details. |
| **Not production-ready for billing** | Stripe subscriptions created this way cannot charge real customers. |
| **No redirect mechanism** | The workflow always returns `GeneralWorkflowResponse` which results in `status: UNBLOCKED` immediately — no way to pause the flow for external input. |

---

## Part 2 — Custom Implementation: Stripe Checkout with Real Payment Collection

### 2.1 Goals

1. Collect **real payment methods** from new subscribers using Stripe's hosted Checkout page.
2. **Redirect** the subscriber's browser to Stripe Checkout during the subscription flow.
3. **Resume** the APIM subscription workflow automatically after the subscriber enters their card.
4. Support subscribers with **multiple applications** subscribing to APIs (reuse existing payment method).

### 2.2 Solution Architecture

The full flow spans three phases. The sequence diagram below shows every participant and every message in order.

```mermaid
sequenceDiagram
    autonumber

    actor User as 👤 Subscriber (Browser)
    participant DP  as DevPortal UI<br/>(override/src)
    participant API as Store REST API<br/>POST /subscriptions
    participant PLG as Stripe Plugin<br/>(dropins JAR)
    participant DB  as APIM Database
    participant PA  as Stripe<br/>Platform Account
    participant SC  as Stripe Checkout<br/>(Hosted Page)
    participant WH  as Webhook WAR<br/>/api/am/stripe/webhook
    participant CA  as Stripe<br/>Connected Account

    %% ════════════════════════════════════════════════════════
    rect rgb(230, 245, 255)
        Note over User,PA: Phase 1 — Subscribe & Redirect
        User  ->>  DP  : Click "Subscribe to API"
        DP    ->>  API : POST /subscriptions<br/>{apiId, applicationId, tier}
        API   ->>  PLG : monetizeSubscription(workflowDTO, api)

        PLG   ->>  PA  : Session.create()<br/>mode=SETUP, currency, metadata<br/>{workflowRef, subscriberId, appId, tier, ...}
        PA    -->> PLG : Checkout Session<br/>{id, url}

        PLG   ->>  DB  : Save checkout session<br/>(session_id, workflowRef, subscriberId)
        PLG   ->>  DB  : Set workflow status = CREATED (pending)

        PLG   -->> API : HttpWorkflowResponse<br/>{redirectUrl = session.url}
        API   -->> DP  : HTTP 200<br/>{status: "ON_HOLD",<br/> redirectionParams: {redirectUrl: "https://checkout.stripe.com/..."}}
        DP    ->>  SC  : window.location.href = redirectUrl<br/>(browser redirected to Stripe)
    end

    %% ════════════════════════════════════════════════════════
    rect rgb(255, 245, 220)
        Note over User,SC: Phase 2 — Card Entry on Stripe (APIM not involved)
        User  ->>  SC  : Enter card details & click Pay
        SC    -->> User: Show confirmation screen
    end

    %% ════════════════════════════════════════════════════════
    rect rgb(230, 255, 235)
        Note over SC,CA: Phase 3 — Webhook Resumes the Workflow
        SC    ->>  WH  : POST /api/am/stripe/webhook<br/>Stripe-Signature: t=...,v1=...<br/>{type: "checkout.session.completed", ...}

        WH    ->>  WH  : Verify HMAC-SHA256 signature<br/>using webhook secret from api-manager.xml

        WH    ->>  DB  : retrieveWorkflowFromInternalReference<br/>(workflowRef from session metadata)
        DB    -->> WH  : WorkflowDTO (status=CREATED)

        WH    ->>  PLG : WorkflowExecutor.complete(workflowDTO)<br/>attributes["checkoutSessionId"] = session.id

        Note over PLG,CA: completeStripeCheckoutSubscription()

        PLG   ->>  PA  : Session.retrieve(checkoutSessionId)
        PA    -->> PLG : Session {setup_intent}

        PLG   ->>  PA  : SetupIntent.retrieve(setup_intent_id)
        PA    -->> PLG : SetupIntent {payment_method_id}

        PLG   ->>  PA  : Customer.create()<br/>{payment_method, invoice_settings.default_payment_method}
        PA    -->> PLG : Platform Customer {id}
        PLG   ->>  DB  : Save platform customer

        PLG   ->>  CA  : PaymentMethod.create()<br/>{clone from platform customer}
        CA    -->> PLG : Cloned PaymentMethod {id}

        PLG   ->>  CA  : Customer.create()<br/>{cloned_pm, invoice_settings.default_payment_method}
        CA    -->> PLG : Shared Customer {id}
        PLG   ->>  DB  : Save shared customer

        PLG   ->>  CA  : Subscription.create()<br/>{shared_customer_id, plan_id}
        CA    -->> PLG : Subscription {id}
        PLG   ->>  DB  : Save subscription

        PLG   ->>  DB  : Update checkout session → COMPLETED
        PLG   ->>  DB  : Update subscription status → UNBLOCKED

        WH    -->> SC  : HTTP 200 {"received": true}<br/>(Stripe stops retrying)

        SC    ->>  User: Redirect to checkoutSuccessUrl
    end
```

### 2.3 Component Changes

#### A. `StripeSubscriptionCreationWorkflowExecutor` (Plugin JAR)

Three new decision points were added to `monetizeSubscription()`:

**Decision 1 — New Subscriber (no platform customer)**
Instead of creating a platform customer with `tok_visa`, the executor now:
1. Creates a **Stripe Checkout Session** in `SETUP` mode on the platform account.
2. Stores the session ID and workflow reference in the `AM_STRIPE_CHECKOUT_SESSIONS` database table.
3. Sets the workflow status to `CREATED` (pending — do not approve yet).
4. Returns an `HttpWorkflowResponse` with the Checkout Session URL as the redirect URL.
5. APIM serialises this into `redirectionParams` in the subscription API response.

**Decision 2 — Platform Customer exists, new Application (no shared customer)**
When the subscriber already has a platform customer (from a previous subscription) but subscribes with a new application:
- The executor checks if the platform customer has a `default_payment_method` set.
- If **yes** (created via Checkout): clones the payment method to the connected account and creates a shared customer with that payment method as default. No new Checkout redirect needed.
- If **no** (created via legacy `tok_visa` path): falls back to the old `Token.create` approach.

**Decision 3 — Both customers exist**
Proceeds directly to creating the Stripe Subscription (unchanged from default).

#### B. DevPortal UI Override (`override/src/app/components/`)

Two components were updated in the DevPortal override directory (takes precedence over source at runtime):

- `Applications/Details/Subscriptions.jsx` — the subscriptions panel inside the Application detail view.
- `Apis/Details/Credentials/Credentials.jsx` — the credentials/subscription panel inside the API detail view.

When the subscription API responds with `status: ON_HOLD`, the UI now:
1. Reads the `redirectionParams` field from the response body.
2. Parses it as JSON to extract `redirectUrl`.
3. Immediately redirects the browser (`window.location.href = redirectUrl`) to the Stripe Checkout page.
4. Falls back to showing a "pending approval" message if no redirect URL is present.

Previously, these override files called a custom `/api/am/stripe/checkout-url` endpoint, which failed because it looked up the checkout session using the subscription UUID, but the database stored a numeric workflow reference — causing a mismatch and returning 404.

#### C. Stripe Webhook WAR (`api#am#stripe.war`)

A standalone Java web application (WAR) deployed at `/api/am/stripe/` on the Carbon server. It exposes:

- `POST /api/am/stripe/webhook` — receives `checkout.session.completed` events from Stripe.

Key design decisions:
- **No stripe-java dependency** — stripe-java 24.x requires Gson 2.10+ (`GsonBuilder.addReflectionAccessFilter`), but the Carbon OSGi classloader exposes an older Gson version that shadows the WAR's bundled copy, causing `NoSuchMethodError` at startup. Instead, Stripe signature verification is implemented natively using Java's `javax.crypto.Mac` (HMAC-SHA256).
- **Jackson for JSON parsing** — the event payload is parsed with Jackson's `ObjectMapper` (already bundled via `jackson-jaxrs-json-provider`), eliminating the Gson dependency entirely.
- **Delegates workflow completion to the plugin** — the WAR only extracts the `workflowReference` from the session metadata and calls `WorkflowExecutorFactory.getWorkflowExecutor().complete()`. The actual Stripe customer/subscription creation runs inside `StripeSubscriptionCreationWorkflowExecutor.complete()` in the plugin OSGi bundle.

### 2.4 End-to-End Custom Flow

```mermaid
sequenceDiagram
    participant U as User (Browser)
    participant DP as WSO2 DevPortal
    participant API as APIM Store API
    participant WF as StripeSubscriptionWorkflowExecutor
    participant DB as APIM Database
    participant PA as Stripe Platform Account
    participant SC as Stripe Checkout (hosted)
    participant WH as Webhook WAR<br/>/api/am/stripe/webhook
    participant CA as Stripe Connected Account

    Note over U,CA: ─── Phase 1: Subscription Request ───

    U->>DP: Subscribe to API (new subscriber)
    DP->>API: POST /subscriptions
    API->>WF: monetizeSubscription()
    WF->>PA: Create Checkout Session (SETUP mode)<br/>metadata: {workflowReference, subscriberId,<br/>applicationId, tierName, apiUuid, ...}
    PA-->>WF: session {id, url}
    WF->>DB: Save checkout session<br/>(session_id, workflow_ref, subscriber_id, ...)
    WF->>DB: Set workflow status = CREATED (pending)
    WF-->>API: HttpWorkflowResponse<br/>redirectUrl = session.url
    API-->>DP: {"status":"ON_HOLD",<br/>"redirectionParams":{"redirectUrl":"https://checkout.stripe.com/..."}}
    DP->>U: window.location.href = redirectUrl

    Note over U,CA: ─── Phase 2: User Enters Card on Stripe ───

    U->>SC: Open Stripe Checkout page
    U->>SC: Enter card details and confirm
    SC-->>U: Redirect to checkoutSuccessUrl?session_id=xxx

    Note over U,CA: ─── Phase 3: Webhook Resumes Workflow ───

    SC->>WH: POST /api/am/stripe/webhook<br/>Stripe-Signature: t=...,v1=...
    WH->>WH: Verify HMAC-SHA256 signature<br/>(native javax.crypto.Mac)
    WH->>WH: Parse event JSON (Jackson)<br/>event.type = "checkout.session.completed"
    WH->>DB: retrieveWorkflowFromInternalReference(workflowReference)
    DB-->>WH: WorkflowDTO (CREATED status)
    WH->>WH: Set workflowDTO.attributes["checkoutSessionId"] = session.id
    WH->>WH: Set workflowDTO.status = APPROVED
    WH->>WF: WorkflowExecutor.complete(workflowDTO)

    Note over WF,CA: ─── completeStripeCheckoutSubscription() ───

    WF->>PA: Session.retrieve(checkoutSessionId)
    PA-->>WF: session (with setup_intent)
    WF->>PA: SetupIntent.retrieve(setup_intent_id)
    PA-->>WF: paymentMethodId
    WF->>PA: Customer.create(paymentMethodId,<br/>invoice_settings.default_payment_method)
    PA-->>WF: platform_customer_id
    WF->>DB: Save platform customer
    WF->>CA: PaymentMethod.create(clone from platform)<br/>→ cloned_payment_method_id
    WF->>CA: Customer.create(cloned_pm,<br/>invoice_settings.default_payment_method)
    CA-->>WF: shared_customer_id
    WF->>DB: Save shared customer
    WF->>CA: Subscription.create(shared_customer_id, plan_id)
    CA-->>WF: subscription_id
    WF->>DB: Save subscription
    WF->>DB: Mark checkout session = COMPLETED
    WF->>DB: Update subscription status = UNBLOCKED
    WH-->>SC: HTTP 200 {"received":true}
```

### 2.5 Second Application Subscription Flow (Returning Subscriber)

When a subscriber who already went through Checkout creates a **second application** and subscribes to an API, the flow is shorter — no new Checkout is needed:

```mermaid
sequenceDiagram
    participant U as User (Browser)
    participant DP as WSO2 DevPortal
    participant API as APIM Store API
    participant WF as StripeSubscriptionWorkflowExecutor
    participant PA as Stripe Platform Account
    participant CA as Stripe Connected Account

    U->>DP: Subscribe to API (new app, existing subscriber)
    DP->>API: POST /subscriptions
    API->>WF: monetizeSubscription()

    Note over WF: Platform customer EXISTS<br/>Shared customer does NOT exist

    WF->>PA: Customer.retrieve(platform_customer_id)
    PA-->>WF: customer.invoice_settings.default_payment_method = pm_xxx
    WF->>CA: PaymentMethod.create(clone pm_xxx from platform)
    CA-->>WF: cloned_pm_id
    WF->>CA: Customer.create(cloned_pm,<br/>invoice_settings.default_payment_method = cloned_pm)
    CA-->>WF: shared_customer_id
    WF->>CA: Subscription.create(shared_customer_id, plan_id)
    CA-->>WF: subscription_id
    WF-->>API: WorkflowStatus.APPROVED
    API-->>DP: {"status": "UNBLOCKED"}
    DP-->>U: Subscription Active (no redirect needed)
```

### 2.6 Stripe Webhook WAR — Signature Verification Detail

Stripe signs every webhook delivery with HMAC-SHA256. The verification logic (implemented natively, no stripe-java) works as follows:

```mermaid
flowchart TD
    A[POST /api/am/stripe/webhook] --> B{Stripe-Signature<br/>header present?}
    B -- No --> Z1[400 Bad Request]
    B -- Yes --> C{Webhook secret<br/>configured?}
    C -- No --> Z2[500 Internal Server Error]
    C -- Yes --> D[Parse header:<br/>t=timestamp, v1=signature]
    D --> E{timestamp within<br/>±5 minute window?}
    E -- No --> Z3[400 Replay Attack Rejected]
    E -- Yes --> F[Compute<br/>HMAC-SHA256 of<br/>timestamp.payload<br/>using webhookSecret]
    F --> G{computedSig<br/>== v1 signature?}
    G -- No --> Z4[400 Invalid Signature]
    G -- Yes --> H[Parse event JSON<br/>with Jackson]
    H --> I{event.type?}
    I -- checkout.session.completed --> J[Extract workflowReference<br/>from session metadata]
    I -- other --> K[Ignore — return 200]
    J --> L[Load WorkflowDTO from DB]
    L --> M[Set checkoutSessionId attribute]
    M --> N[WorkflowExecutor.complete()]
    N --> O[200 OK — Stripe stops retrying]
```

### 2.7 Database Tables Used

| Table | Purpose |
|---|---|
| `AM_WORKFLOWS` | Stores workflow state (CREATED → APPROVED). Primary key for resumption. |
| `AM_STRIPE_PLATFORM_CUSTOMER` | Maps APIM subscriber ID to Stripe platform customer ID |
| `AM_STRIPE_SHARED_CUSTOMER` | Maps application + API provider to Stripe connected-account customer ID |
| `AM_STRIPE_SUBSCRIPTION` | Maps APIM subscription to Stripe subscription ID |
| `AM_STRIPE_CHECKOUT_SESSIONS` | (New) Stores active checkout sessions: session ID, workflow reference, subscriber, status |

### 2.8 Configuration

**`workflow-extensions.xml`** — two new properties on the executor:
```xml
<Executor name="org.wso2.apim.monetization.impl.workflow.StripeSubscriptionCreationWorkflowExecutor">
    <Property name="checkoutSuccessUrl">https://&lt;devportal-host&gt;:9443/devportal/subscription/success</Property>
    <Property name="checkoutCancelUrl">https://&lt;devportal-host&gt;:9443/devportal/subscription/cancel</Property>
</Executor>
```

**`api-manager.xml`** — webhook signing secret:
```xml
<Monetization>
    <StripeWebhookSecret>whsec_your_signing_secret_here</StripeWebhookSecret>
</Monetization>
```

**Stripe Dashboard** — webhook endpoint registration:
- Endpoint URL: `https://<apim-host>:9443/api/am/stripe/webhook`
- Event: `checkout.session.completed`

---

## Part 3 — Issues Resolved During Development

| # | Issue | Root Cause | Fix |
|---|---|---|---|
| 1 | `Missing required param: currency` | stripe-java 24.x requires `currency` on Checkout Session for setup mode | Retrieve the Stripe Plan for the tier before creating the session; pass the plan's currency to `SessionCreateParams.setCurrency()` |
| 2 | `redirectionParams: null` in subscription response | `StripeSubscriptionCreationWorkflowExecutor` returned `GeneralWorkflowResponse` — APIM only populates `redirectionParams` when the response is `instanceof HttpWorkflowResponse` | Changed return type to `HttpWorkflowResponse` with `setRedirectUrl(checkoutSession.getUrl())` |
| 3 | UI redirect not working (`/checkout-url` returned 404) | DevPortal override files called `GET /api/am/stripe/checkout-url?subscriptionId={UUID}` but the DB stores a numeric workflow reference (`9`), not a UUID — query returned 0 rows | Removed the `/checkout-url` call; read `redirectionParams` directly from the subscription POST response |
| 4 | Webhook `NoSuchMethodError: GsonBuilder.addReflectionAccessFilter` | `stripe-java 24.x` calls Gson 2.10+ API, but the Carbon OSGi classloader exposes an older Gson that shadows the WAR's bundled copy | Removed stripe-java from the webhook WAR entirely; implemented HMAC-SHA256 verification natively with `javax.crypto.Mac` and parsed event JSON with Jackson |
| 5 | `No attached payment method` on Subscription.create | `createSharedCustomerWithPaymentMethod` attached the payment method to the customer but did not set `invoice_settings.default_payment_method` — Stripe requires this for subscription billing | Added `invoice_settings.default_payment_method = clonedPm.getId()` to the shared customer creation params |
| 6 | Second-application subscription fails — `Token.create: customer must have an active payment source` | The legacy `createSharedCustomer` uses `Token.create` which requires a Stripe **source** (legacy card) on the platform customer. Checkout-created customers have a `payment_method` instead — incompatible with the Token API | Added `getDefaultPaymentMethodId()` helper: if the platform customer has a default payment method (Checkout path), use `createSharedCustomerWithPaymentMethod`; otherwise fall back to the legacy `createSharedCustomer` |

---

## Part 4 — Deployment Checklist

```
[ ] Build the plugin JAR:
      cd wso2-am-stripe-plugin && mvn clean package
      Copy target/org.wso2.apim.monetization.impl-*.jar
          → <APIM_HOME>/repository/components/dropins/

[ ] Build the webhook WAR:
      cd wso2-am-stripe-webhook && mvn clean package
      Copy target/api#am#stripe.war
          → <APIM_HOME>/repository/deployment/server/webapps/

[ ] DevPortal UI overrides are already in place at:
      <APIM_HOME>/repository/deployment/server/webapps/devportal/
        override/src/app/components/Applications/Details/Subscriptions.jsx
        override/src/app/components/Apis/Details/Credentials/Credentials.jsx

[ ] Configure workflow-extensions.xml:
      Add checkoutSuccessUrl and checkoutCancelUrl properties

[ ] Configure api-manager.xml:
      Add <StripeWebhookSecret>whsec_...</StripeWebhookSecret> under <Monetization>

[ ] Register webhook endpoint in Stripe Dashboard:
      URL: https://<host>:9443/api/am/stripe/webhook
      Event: checkout.session.completed

[ ] Restart WSO2 APIM
```
