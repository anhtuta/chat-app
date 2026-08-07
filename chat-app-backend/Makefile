.PHONY: help start start.all upd upbd upbd.instance-2
.PHONY: build.fe run.local run.be debug.prepare run.fe run.bot
.PHONY: stop restart restart.be2 logs clean status check.env check.env.local
.PHONY: db.migrate db.info db.clean db.repair
.PHONY: test.be test.be.ut test.be.it test.fe test.e2e

.DEFAULT_GOAL := help

# Check and create .env file if it doesn't exist (used by docker-compose interpolation)
check.env:
	@if [ ! -f .env ]; then \
		echo "📝 Creating .env file from example..."; \
		cp .env.example .env 2>/dev/null || echo "⚠️  .env.example not found, using defaults"; \
	fi

# Host-side Spring Boot / Flyway (make run.be, make db.migrate, …). Not used by docker-compose.
check.env.local:
	@if [ ! -f .env.local ]; then \
		echo "📝 Creating .env.local file from example..."; \
		cp .env.local.example .env.local 2>/dev/null || echo "⚠️  .env.local.example not found, using defaults"; \
	fi

# Host-side Spring Boot / Flyway (not used by docker-compose).
# Source .env.local instead of xargs+export — empty xargs output would re-export the
# whole shell environment and break on values containing quotes.
LOAD_ENV_LOCAL = set -a && . ./.env.local && set +a

help:
	@echo "Chat App Docker Commands"
	@echo "======================="
	@echo ""
	@echo "Available targets:"
	@echo "  make start       - Build and start all services (alias of start.all)"
	@echo "  make start.all   - Build and start all services"
	@echo "  make upd         - Start infra only (Postgres/Redis/RabbitMQ) for IDE debugging"
	@echo "  make build.fe    - Build frontend and copy to Spring Boot static resources"
	@echo "  make run.local   - Run Spring Boot app locally with .env.local variables loaded"
	@echo "  make run.be      - Run Spring Boot backend only (Terminal 1)"
	@echo "  make run.fe      - Run React dev server (Terminal 2)"
	@echo "  make stop        - Stop all running services"
	@echo "  make restart     - Restart all services"
	@echo "  make logs        - View application logs (follow mode)"
	@echo "  make status      - Show service status"
	@echo "  make clean       - Remove stopped containers and volumes"
	@echo ""
	@echo "Database Migration Commands:"
	@echo "  make db.migrate  - Run Flyway migrations"
	@echo "  make db.info     - Show migration status"
	@echo "  make db.clean    - Drop all objects in schema (use with caution!)"
	@echo "  make db.repair   - Repair Flyway schema history"
	@echo ""
	@echo "Backend Test Commands:"
	@echo "  make test.be              - Run all backend tests (unit + integration)"
	@echo "  make test.be.ut           - Run unit tests only (fast, no Spring context)"
	@echo "  make test.be.it           - Run integration tests only (@DataJpaTest, @SpringBootTest)"
	@echo ""
	@echo "  make help        - Show this help message"
	@echo ""
	@echo "Development Workflow:"
	@echo "  1. make upd           - Start infrastructure"
	@echo "  2. make db.migrate    - Run database migrations"
	@echo "  3. Start BE and FE: use one of these:"
	@echo "  3.1. make run.be      - Terminal 1: Start Backend (http://localhost:9010)"
	@echo "       make run.fe      - Terminal 2: Start Frontend (http://localhost:3000)"
	@echo "  3.2. make run.local   - Start Backend with embedded frontend (http://localhost:9010)"
	@echo "  4. Visit http://localhost:9010/login.html to login"
	@echo "  5. After login, you'll be redirected to http://localhost:3000"

start: start.all

start.all: check.env
	@echo "🚀 Starting Chat App with Docker..."
	@echo "🔨 Building frontend..."
	@cd ../chat-app-frontend && npm run build:spring && cd ../chat-app-backend
	@echo "🔨 Building and starting services..."
	docker-compose --profile multi-instance up -d --build
	@echo "✅ Services started!"

upd: check.env
	@echo "Starting infrastructure services (Postgres, Redis, RabbitMQ) for IDE debugging..."
	@echo "Bringing up infrastructure containers without chat instances..."
	docker-compose up -d postgres redis rabbitmq prometheus grafana minio instance-2
	@echo "Infra ready. Run the chat application from your IDE against postgres:5432, redis:6379, rabbitmq:5672."

upbd: check.env
	@echo "Starting infrastructure services (Postgres, Redis, RabbitMQ) for IDE debugging..."
	@echo "Bringing up infrastructure containers without chat instances..."
	docker-compose up -d --build postgres redis rabbitmq prometheus grafana minio instance-2
	@echo "Infra ready. Run the chat application from your IDE against postgres:5432, redis:6379, rabbitmq:5672."

upbd.instance-2: check.env
	@echo "Building and starting instance-2..."
	docker-compose up -d --build instance-2
	@echo "✅ instance-2 started!"

# Build FE and copy to BE static resources
build.fe:
	@echo "🔨 Building frontend and copying to Spring Boot..."
	@cd ../chat-app-frontend && npm run build:spring && cd ../chat-app-backend
	@echo "✅ Frontend built and copied to src/main/resources/static/"

# Build FE, copy to BE static resources, and run BE
run.local: check.env.local build.fe
	@echo "🚀 Running Spring Boot app locally with .env.local variables..."
	@$(LOAD_ENV_LOCAL) && ./mvnw spring-boot:run

# Similar to run.local but without building frontend
# (Only run BE, use it when static files are already in place, or when running FE separately)
run.be: check.env.local
	@echo "🚀 Running Spring Boot app locally with .env.local variables..."
	@$(LOAD_ENV_LOCAL) && ./mvnw spring-boot:run

test.be: test.be.ut test.be.it

test.be.ut:
	@echo "🚀 Running backend unit tests..."
	./mvnw test

# integration-test runs ITs but does not fail the build on failures; verify checks the results.
# Avoid `mvn verify` here so unit tests (Surefire) are not re-run — use test.be for both.
test.be.it:
	@echo "🚀 Running backend integration tests..."
	./mvnw failsafe:integration-test failsafe:verify

test.fe:
	@echo "🚀 Running frontend tests..."
	@cd ../chat-app-frontend && npm test

# Used by Cursor "Debug Backend (make run.be)" launch config.
# Ensures .env.local exists and compiles with the same env vars as make run.be.
debug.prepare: check.env.local
	@echo "🔧 Preparing backend for IDE debug (load .env.local + compile)..."
	@$(LOAD_ENV_LOCAL) && ./mvnw -q -DskipTests compile
	@echo "✅ Ready — start Debug Backend (make run.be) in Cursor"

run.fe:
	@echo "🚀 Running React dev server (Terminal 2)..."
	@echo "📝 API proxy configured to http://localhost:9010"
	@cd ../chat-app-frontend && npm start

run.bot:
	@echo "🚀 Running Bot simulator..."
	cd ../bot-simulator && ./mvnw spring-boot:run

test.e2e:
	@echo "🚀 Running E2E tests..."
	@cd ../e2e && E2E_BASE_URL=http://localhost:9010 E2E_SKIP_WEBSERVER=1 pnpm test

stop:
	@echo "🛑 Stopping Chat App with Docker..."
	docker-compose --profile multi-instance stop
	@echo "✅ Services stopped!"

restart:
	@echo "🔄 Restarting services..."
	docker-compose --profile multi-instance restart
	@echo "✅ Services restarted!"

restart.be2:
	@echo "🔄 Restarting instance-2..."
	docker compose up -d --build instance-2
	@echo "✅ instance-2 restarted!"

logs:
	docker-compose --profile multi-instance logs -f instance-1

# Database Migration Commands
db.migrate: check.env.local
	@echo "🔄 Running Flyway migrations..."
	@$(LOAD_ENV_LOCAL) && ./mvnw flyway:migrate
	@echo "✅ Migrations complete!"

# Skip executing migrations but still update the schema history to the target version.
# Use it when we've already run migrations manually, and flyway_schema_history table doesn't have executed versions.
# Please update Dflyway.target to the version you want to mark as executed.
db.migrate.skip: check.env.local
	@echo "🔄 Running Flyway migrations (skip)..."
	@$(LOAD_ENV_LOCAL) && ./mvnw flyway:migrate -Dflyway.target=4 -Dflyway.skipExecutingMigrations=true
	@echo "✅ Migrations complete!"

db.info: check.env.local
	@echo "📊 Checking migration status..."
	@$(LOAD_ENV_LOCAL) && ./mvnw flyway:info

db.clean: check.env.local
	@echo "⚠️  WARNING: This will drop all objects in the schema!"
	@read -p "Are you sure? [y/N] " -n 1 -r; \
	echo; \
	if [[ $$REPLY =~ ^[Yy]$$ ]]; then \
		$(LOAD_ENV_LOCAL) && ./mvnw flyway:clean; \
		echo "✅ Schema cleaned!"; \
	else \
		echo "❌ Cancelled"; \
	fi

db.repair: check.env.local
	@echo "🔧 Repairing Flyway schema history..."
	@$(LOAD_ENV_LOCAL) && ./mvnw flyway:repair

status:
	docker-compose ps

clean:
	@echo "🧹 Cleaning up stopped containers and volumes..."
	docker-compose down
	@echo "✅ Cleanup complete!"
