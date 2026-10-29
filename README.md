# Nsure-be

## API access (RBAC)

Every endpoint and the permission that opens it is listed in one file:
[`ApiAccessRules.java`](src/main/java/com/nsure/security/config/ApiAccessRules.java). The rule types are in
[`ApiAccess.java`](src/main/java/com/nsure/security/config/ApiAccess.java).

How a request is decided:

1. **Super Admin** and **Super user** pass every rule. Both role names are checked in code before any permission
   lookup, so they keep full access even if a grant row is removed.
2. Everyone else needs a **permission code** that one of their roles holds (for example `READ_CLAIM`,
   `ASSESS_CLAIM`). Rules name codes, never role names, so any role granted a code in the Access screen gets
   that endpoint. A role with `ALL_FUNCTIONS` passes everything; `ALL_FUNCTIONS_READ` passes every `READ_*` rule.
3. A request that matches no rule is **denied** (403, or 401 when not signed in).
4. Portal customers (`Policyholder`) reach only `/me` and `/portfolio/shop/**`.

Services still check the same codes before they write, so the URL rule and the service never disagree.

| Area | Endpoints | Permission | Default holders |
| --- | --- | --- | --- |
| Public | `GET /actuator/health`, `/reference/**`, `/public/**` | none | everyone |
| Profile | `GET /me` | signed in | every user |
| Customer portal | `/portfolio/shop/**` | Policyholder | customers |
| Customers | `GET /portfolio/customers` · `POST` · `…/kyc` · `…/edd` | `READ_CUSTOMER` · `CREATE_CUSTOMER` · `KYC_CUSTOMER` · `EDD_CUSTOMER` | Onboarding Specialist, Claims Assessor (read) |
| Applications | `GET` · `POST` · `PUT /portfolio/applications/*` | `READ_` · `CREATE_` · `UPDATE_APPLICATION` | Super roles |
| Policies | `GET` · `POST /portfolio/policies`, policy actions, receipts, schemes | `READ_POLICY` · `CREATE_POLICY` · `UPDATE_POLICY` | Actuary, Claims Assessor (read) |
| Claims | `GET` · `POST /portfolio/claims`, payee, checks | `READ_CLAIM` · `CREATE_CLAIM` · `UPDATE_CLAIM` | Claims Assessor, Actuary (read) |
| Claim actions | `POST /portfolio/claims/*/assess` · `approve` · `reject` · `pay` | `ASSESS_` · `APPROVE_` · `REJECT_` · `SETTLE_CLAIM` | Claims Assessor |
| Products | `GET /portfolio/products` | any staff | all staff |
| Product configs | `GET` · `POST` · `PUT /portfolio/product-configs` | `READ_` · `CREATE_` · `UPDATE_PRODUCT` | Product Configurator, Actuary |
| Rate tables | `GET /admin/rate-tables/**` · `PUT …/grid` | `READ_RATE_TABLE` or `rate.read` · `UPDATE_RATE_TABLE` or `rate.write` | Product Configurator, Actuary |
| Controls | `GET /admin/controls` | `READ_CONTROL` or `controls.read` | Actuary, Claims Assessor |
| Audit | `GET /audits/**` · `POST …/approve` · `…/reject` | `READ_AUDIT` · a `CHECKER_*` code | Super roles |
| Offices | `GET /offices` | `READ_OFFICE` | Super roles |
| Access admin | `/users/**`, `/roles/**`, `/permissions/**` | Super Admin / Super user | Super roles |
| Group schemes | `/group/**` | `READ_SCHEME` · `MANAGE_SCHEME` · `PROCESS_CENSUS` · `UNDERWRITE_MEMBER` · `BILL_SCHEME` | Group Administrator (`grpadmin`) |
| Subledger | `/finance/**` | `READ_FINANCE` · `MANAGE_FINANCE` · `CLOSE_PERIOD` · `EXPORT_GL` | Finance Controller (`finance`) |
| Distribution | `/distribution/**` | `READ_DISTRIBUTION` · `MANAGE_DISTRIBUTION` · `PAY_COMMISSION` | Distribution Administrator (`distribution`) |
| Reinsurance | `/reinsurance/**` | `READ_REINSURANCE` · `MANAGE_REINSURANCE` · `APPROVE_TREATY` | Reinsurance Officer (`reinsurance`) |
| Communications | `/communications/**` | `READ_COMMUNICATION` · `MANAGE_COMMUNICATION` · `APPROVE_TEMPLATE` | Service Agent (`service`), Operations Manager (`ops`) |
| Service desk | `/service/**`, customer `/portfolio/shop/cases` | `READ_SERVICE_CASE` · `MANAGE_SERVICE_CASE` | Service Agent (`service`) |
| Integration | `/integration/**` | `READ_INTEGRATION` · `MANAGE_INTEGRATION` | Operations Manager (`ops`) |
| Management report | `GET /reports/management` | `READ_REPORT` | Operations Manager, Finance Controller, Group Administrator |

## Nsure Life modules and the event bus

The deployment model is **one dedicated installation per insurer** (no multitenancy) — see
[`docs/NSURE_LIFE_IMPLEMENTATION_PLAN.md`](../docs/NSURE_LIFE_IMPLEMENTATION_PLAN.md).

Business changes publish events through the transactional outbox (`com.nsure.integration.Outbox`) in the same
transaction. `OutboxDispatcher` delivers them every 5 s (`nsure.integration.dispatch-delay-ms`) to each
`DomainEventHandler` — subledger posting, commission, reinsurance cession, customer messages — one delivery row per
handler, retried with backoff and replayable from the Integration monitor. Handlers are idempotent on the event id,
so a replay never posts, pays or messages twice. Demo logins for the new roles all use password `password`.

### Adding an endpoint

1. Add the controller method.
2. Add its rule to `ApiAccessRules`. `EndpointCoverageTest` fails the build if you forget, and also fails if a
   rule names a permission code that is not seeded in `app_permission`.
3. For a new code, seed it in a Liquibase changeset and grant it to the roles that need it.
