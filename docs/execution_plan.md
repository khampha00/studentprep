# StudentPrep Master Execution Plan (Agent Orchestration DAG)

Because our AI agents are highly specialized, they must be invoked in a strict, sequential order to prevent them from attempting to build components that depend on non-existent foundations.

## Phase 1: Infrastructure & Bootstrapping
1. **`devops-architect`**
   * **Action:** Generate `docker-compose.yml` and CI/CD pipelines at the project root.
   * **Why first?** We need the PostgreSQL, Redis, and MinIO containers running so subsequent agents can test database connections and schemas.
2. **`modulith-architect`**
   * **Action:** Generate `pom.xml`, `Application.java`, and the empty domain package structure (e.g., `com.studentprep.exam`) inside the `/backend` folder.
   * **Why second?** All backend agents require the Maven project and package boundaries to exist before they can write classes.

## Phase 2: Data Layer & Security
3. **`schema-engineer`**
   * **Action:** Write Flyway migrations (including the `V2__Seed_Data.sql`) and JPA `@Entity` classes inside the `/backend` packages.
   * **Why third?** Business logic cannot be written until the database schema and Object-Relational Mappings (ORM) are locked in.
4. **`security-engineer`**
   * **Action:** Configure Spring Security, JWT filters, and Role-Based Access Control.
   * **Why fourth?** We must secure the backend endpoints before the domain logic is implemented, preventing agents from writing controllers that accidentally bypass security.

## Phase 3: Core Business Logic
5. **`domain-logic-implementer`**
   * **Action:** Build out the Services, REST Controllers, and Spring Modulith Event Listeners (Transactional Outbox) for all modules.
   * **Why fifth?** This agent will connect the security context, the database entities, and the API endpoints into functional business logic.

## Phase 4: Frontend Foundations
6. **`frontend-state-architect`**
   * **Action:** Initialize the Vite/React app inside the `/frontend` folder. Configure Redux Toolkit, Dexie.js (IndexedDB), and the Service Worker (PWA).
   * **Why sixth?** The UI cannot be built until the state management, routing, and offline-first syncing engine are fully configured.

## Phase 5: The User Interface
7. **`frontend-ui-developer`**
   * **Action:** Build the visual React components (using Tailwind CSS, shadcn/ui, and KaTeX) and connect them to the Redux store.
   * **Why last?** UI development is the final layer. This agent will consume the Redux actions (Phase 4) which in turn call the secure backend endpoints (Phase 3).
