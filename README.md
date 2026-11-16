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
| Policyholder / End User | **none** — protected role, access by ownership only | `customer` |

Demo passwords are `password`. Break-glass codes — `UPDATE_CONTROL` (controls.write), `OVERRIDE_CLAIM_FOUR_EYES`
(claim.approve.override), `PUBLISH_OWN_RATE_TABLE` (rate.publish.self) — are granted to **no role**, the administrator
included; using one needs a written reason that is recorded.

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

### Adding an endpoint

1. Add the controller method.
2. Add its rule to `ApiAccessRules`. `EndpointCoverageTest` fails the build if you forget, and also fails if a
   rule names a permission code that is not seeded in `app_permission`.
3. For a new code, seed it in a Liquibase changeset and grant it to the roles that need it.
