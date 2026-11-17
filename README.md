# Nsure-be

## Access control (RBAC)

One model, from `docs/IMPLEMENT_RBAC.md`: **users hold roles, roles hold permission codes** (`ACTION_ENTITY`, e.g.
`APPROVE_CLAIM`). The spec's seven roles and grant matrix (spec 04 §4, 11 §2.2, 12 §3.3) are data on top of it, and
each permission code records the spec capability it realises (`app_permission.capability`, e.g. `claim.approve`).
Plan and rationale: [`docs/SPEC_IMPLEMENTATION_PLAN.md`](../docs/SPEC_IMPLEMENTATION_PLAN.md) §1.

How a request is decided:

1. **URL rule** — every endpoint has exactly one rule in
   [`ApiAccessRules.java`](src/main/java/com/nsure/security/config/ApiAccessRules.java): `publicAccess`,
   `authenticated`, `customer` (portal, ownership-checked in the service) or `anyOf(CODE…)`. A request no rule matches
   is denied. `EndpointCoverageTest` fails the build for an endpoint without a rule.
2. **Service check** — the service that does the work checks the same code (`validateHasPermissionTo`) before it
   writes, so batch jobs and internal calls are covered too.
3. **Deny by default** (Nsure Life NS-SH11-002): a code is held only through an enabled role that grants it, or an
   active emergency grant. There is no wildcard code and no role that passes by name — the administrator role holds
   explicit grants like any other. A **disabled role grants nothing**.
4. **Closed vocabulary** — every code is declared in
   [`PermissionCatalog`](src/main/java/com/nsure/security/domain/PermissionCatalog.java); asking for an unknown code
   throws (A-4). The table and the enum are asserted equal both ways (`RbacMatrixTest`). Codes are never created or
   deleted through the API — only relabelled.
5. **Staff vs customer** — a user is staff only if they hold an enabled role with **desk access**; everyone else is a
   portal customer, scoped to their own records by `customer_ref` (A-11 … A-14). 401 = not signed in, 403 = signed in
   without the permission or outside data scope.

### Roles (seeded)

| Role | Capabilities (spec 04 §4) | Demo login |
|---|---|---|
| Super Admin | every code as an explicit grant, except break-glass codes and reviewing emergency access | `admin`, `secadmin` |
| Product Configurator | product.read/write/activate/delete, tier.write, rate.read/write | `configurator` |
| Actuary | dashboard, claim/policy/payment read, product.read/write, rate.read/write/**publish**, controls.read | `actuary` |
| Onboarding Specialist | customer.read/write (KYC, EDD) | `onboarding` |
| Claims Assessor | dashboard, claim.read/create/assess/approve/reject, policy/payment read, controls.read | `assessor`, `assessor2` |
| Customer Support Representative | dashboard, claim.read/create, policy.read/create/write, payment.read, customer.read (+ service desk, communications) | `support` |
| Compliance & Auditor | read-only: dashboard, claims, policies, payments, customers, audit, rates, controls, reports, finance + finance.export | `compliance` |
| Underwriter | underwriting cases: evidence, decisions within the role's authority limit, risk-increase endorsements | `underwriter` |
| Credit Life Administrator | lenders, loans, disbursements, balances, credit claims and early settlements | `credit` |
| Policyholder / End User | **none** — protected role, access by ownership only | `customer` |

Demo passwords are `password`. These logins exist in demo installations only: migration 079 locks every account on
that published password, and gives them back only in the `demo` Liquibase context (and when no context is given, as
locally). A production migration (`!demo`) leaves no account that accepts `password`; its first administrator comes
from `NSURE_BOOTSTRAP_ADMIN_USERNAME` / `NSURE_BOOTSTRAP_ADMIN_PASSWORD` (12+ characters, used once while no enabled
Super Admin exists; pick a name other than a locked demo login such as `admin`). Break-glass codes — `UPDATE_CONTROL` (controls.write), `OVERRIDE_CLAIM_FOUR_EYES`
(claim.approve.override), `PUBLISH_OWN_RATE_TABLE` (rate.publish.self) — are granted to **no role**, the administrator
included; using one needs a written reason that is recorded.

### Sign-in and one-time codes (NS-SH11-001)

- `POST /auth/session` with HTTP Basic: a portal customer gets the session token at once. **Staff and partners** (any
  role with desk access) get a challenge instead and no token; `POST /auth/session/verify` with
  `{challenge, code}` issues the token once the code is right. Their password is refused (401) everywhere else, so no
  API client can skip the code (`SecondFactorRequiredFilter`).
- Customer sign-up (`POST /public/signup/otp`, then `POST /public/signup` with `challenge` and `otp`) uses the same
  codes, bound to the phone they were sent to.
- Codes (`OneTimeCodes`): one per request, valid `nsure.one-time-codes.ttl-minutes` (5) for
  `nsure.one-time-codes.max-attempts` (5) answers, accepted once, stored only as hashes. In the `dev` profile (the
  default when no profile is chosen) or `demo`, and never together with `prod`, the code is the fixed `123456` and is
  returned with the challenge. Everywhere else it is random and sent through a `OneTimeCodeSender` bean (the messaging
  provider); **without one, sign-up and staff/partner sign-in are refused**, never answered with the fixed code. No
  provider ships yet, and staff accounts hold no phone or e-mail, so a `prod` installation cannot sign staff in until
  both exist.

### Emergency access (NS-SH11-003)

`/admin/emergency-access` gives one break-glass code to one staff member for **1–8 hours** with a written reason
(`GRANT_EMERGENCY_ACCESS`, Super Admin). Nobody grants it to themselves. Every `READ_AUDIT` holder gets an alert when
it is granted or revoked. It lapses on its own or is revoked early, and once it has ended someone other than the
granter and the holder reviews it (`REVIEW_EMERGENCY_ACCESS`, Compliance & Auditor). While active it shows in the
holder's `/me` (`emergencyAccess`) and as a banner in the workspace.

### Guards on the grant table (spec 04 §8)

- A **protected role** (Policyholder / End User, Guest, All) never gets a permission, desk access or an approval limit,
  and cannot be renamed or deleted.
- A role **held by a user linked to a customer record** cannot be armed with permissions or desk access, and an armed
  role cannot be given to such a user (A-19).
- Nobody grants a code they do not hold; only a super admin changes the Super Admin role or a super admin account.
- `UPDATE_USER`, `UPDATE_ROLE` and `PERMISSIONS_ROLE` are maker-checker codes for **everyone**, the administrator
  included: a different person holding the matching `CHECKER_*` approves the change (Access → Approvals). Nobody
  approves their own change (NS-UX-004); the checker on these three cannot be switched off. A user editing their own
  profile skips it. Locally, `admin` makes and `secadmin` approves.
- The Super Admin role keeps `PERMISSIONS_ROLE` and `CHECKER_PERMISSIONS_ROLE`, so a mistaken edit cannot lock every
  administrator out of role grants.
- **Claim approval limits** per role (`/admin/approval-authorities`): no row = unlimited, several roles = the highest
  (spec 04 §5.3). No role is exempt by name.
- There is no office/branch hierarchy: staff are scoped by permission, customers by ownership (`customer_ref`).

### Adding a protected action

1. Add the code to `PermissionCatalog` and seed it (Liquibase) with its `capability` if it realises one.
2. Grant it to the roles that need it in a changeset.
3. Add the URL rule to `ApiAccessRules` and the `validateHasPermissionTo` check in the service.
4. Add the page/action guard to `Nsure-fe/src/access.js` with the same code (spec 04 A-9).

## Nsure Life modules and the event bus

The deployment model is **one dedicated installation per insurer** (no multitenancy) — see
[`docs/NSURE_LIFE_IMPLEMENTATION_PLAN.md`](../docs/NSURE_LIFE_IMPLEMENTATION_PLAN.md).

Business changes publish events through the transactional outbox (`com.nsure.integration.Outbox`) in the same
transaction. `OutboxDispatcher` delivers them every 5 s (`nsure.integration.dispatch-delay-ms`) to each
`DomainEventHandler` — subledger posting, commission, reinsurance cession, customer messages — one delivery row per
handler, retried with backoff and replayable from the Integration monitor. Handlers are idempotent on the event id,
so a replay never posts, pays or messages twice. Only the roles in the Nsure roles spec are shipped (migration 044 removed the six Life-module roles), so the group, distribution,
reinsurance and integration modules are reached through the Super Admin role's explicit grants until their codes are granted to a spec role. Customer
Support Representative (`support`) and Compliance & Auditor (`compliance`) come from migration 045; demo password `password`.

Partner portal logins come from migration 077: `agent` (intermediary INT-00001), `employer` (scheme SCH-001) and `lender` (LND-0001), each with only `USE_PARTNER_PORTAL` and tied to that one partner through `portal_account`. The same migration adds the work queue (`/ops/queue`), workflow definitions and business calendar (`/ops/workflow`), the document register with retention and legal hold (`/ops/documents`; the upload scan result is fixed at Clean because no scanner is connected) and the combined benefit payouts list (`/ops/payouts`).

### Adding an endpoint

1. Add the controller method.
2. Add its rule to `ApiAccessRules`. `EndpointCoverageTest` fails the build if you forget, and also fails if a
   rule names a permission code that is not seeded in `app_permission`.
3. For a new code, seed it in a Liquibase changeset and grant it to the roles that need it.

## Party master (SH01)

- **Identity numbers** are checked by document type on Add customer and self sign-up: by default an Omang is 9 digits
  and a passport 6–20 letters and digits (spaces ignored). These defaults await the insurer's confirmation; set
  `nsure.identity.omang-pattern` / `nsure.identity.passport-pattern` (regular expressions) to change them.
- **Duplicates**: an identity number an active customer already holds is refused on sign-up. Staff get 409 naming
  the holder and may create the record only with `duplicateReason`; it is then referred for duplicate review (listed
  in Data & assistants → Duplicate parties). A merge moves the records as entities under their row versions.
- **Events**: PartyCreated, KycStatusChanged and PartyMerged go through the outbox (references only).
- **KYC evidence**: a customer awaiting verification uploads identity documents from the portal
  (`/portfolio/shop/identity-documents`); staff with `READ_CUSTOMER` open them on the customer record.
- Customers and applications carry a row version; KYC/EDD decisions, application commands and issue honour
  `If-Match` (409 `STALE_VERSION`).

## Individual Life modules (Nsure Life IL02–IL05)

| Module | Package | API | What it does |
|---|---|---|---|
| IL02 Underwriting | `com.nsure.underwriting` | `/underwriting/**` | A case per submitted application. Rules accept a case inside every limit; the rest are referred for evidence and a decision within authority. Changed terms are a numbered offer the applicant accepts before issue. |
| IL03/IL04 Policy servicing | `com.nsure.servicing` | `/servicing/**` | Previewed endorsements (payer, ownership, sum assured, frequency, lives), dated insured lives and age reviews, product-defined contractual benefits (cashback, return of premium, maturity, surrender, paid-up), reinstatement approval and reasoned termination. |
| IL05 Credit life | `com.nsure.credit` | `/credit/**` | Lenders and loans with their own credit life policy; cover on disbursement plus first premium; idempotent lender balances with exceptions; claim split between lender and estate; early settlement with one refund. |

Rules that drive these modules are data, editable in the workspace: underwriting rule sets and authority limits,
product benefit and servicing rules, and the credit product rule. The seeded values are starting points for the
insurer's approved figures.
