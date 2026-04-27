.PHONY: help start start.all start.deps build.fe run.local run.be run.fe stop restart logs clean status check.env db.migrate db.info db.clean db.repair

# Check and create .env file if it doesn't exist
check.env:
	@if [ ! -f .env ]; then \
		echo "📝 Creating .env file from example..."; \
		cp .env.example .env 2>/dev/null || echo "⚠️  .env.example not found, using defaults"; \
	fi

help:
	@echo "Chat App Docker Commands"
	@echo "======================="
	@echo ""
	@echo "Available targets:"
	@echo "  make start       - Build and start all services (alias of start.all)"
	@echo "  make start.all   - Build and start all services"
	@echo "  make start.deps  - Start infra only (Postgres/Redis/RabbitMQ) for IDE debugging"
	@echo "  make build.fe    - Build frontend and copy to Spring Boot static resources"
	@echo "  make run.local   - Run Spring Boot app locally with .env variables loaded"
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
	@echo "  make help        - Show this help message"
	@echo ""
	@echo "Development Workflow:"
	@echo "  1. make start.deps  - Start infrastructure"
	@echo "  2. make db.migrate  - Run database migrations"
	@echo "  3. make run.be      - Terminal 1: Start Backend (http://localhost:9010)"
	@echo "  4. make run.fe      - Terminal 2: Start Frontend (http://localhost:3000)"
	@echo "  5. Visit http://localhost:9010/login.html to login"
	@echo "  6. After login, you'll be redirected to http://localhost:3000"

start: start.all

start.all: check.env
	@echo "🚀 Starting Chat App with Docker..."
	@echo "🔨 Building frontend..."
	@cd chat-app-frontend && npm run build:spring && cd ..
	@echo "🔨 Building and starting services..."
	docker-compose --profile multi-instance up -d --build
	@echo "✅ Services started!"

start.deps: check.env
	@echo "Starting infrastructure services (Postgres, Redis, RabbitMQ) for IDE debugging..."
	@echo "Bringing up infrastructure containers without chat instances..."
	docker-compose up -d postgres redis rabbitmq
	@echo "Infra ready. Run the chat application from your IDE against postgres:5432, redis:6379, rabbitmq:5672."

build.fe:
	@echo "🔨 Building frontend and copying to Spring Boot..."
	@cd chat-app-frontend && npm run build:spring && cd ..
	@echo "✅ Frontend built and copied to src/main/resources/static/"

run.local: check.env build.fe
	@echo "🚀 Running Spring Boot app locally with .env variables..."
	@export $$(cat .env 2>/dev/null | grep -v '^#' | xargs) && ./mvnw spring-boot:run

# Similar to run.local but without building frontend
run.be: check.env
	@echo "🚀 Running Spring Boot app locally with .env variables..."
	@export $$(cat .env 2>/dev/null | grep -v '^#' | xargs) && ./mvnw spring-boot:run

run.fe:
	@echo "🚀 Running React dev server (Terminal 2)..."
	@echo "📝 API proxy configured to http://localhost:9010"
	@cd chat-app-frontend && npm start

stop:
	@echo "🛑 Stopping Chat App with Docker..."
	docker-compose --profile multi-instance stop
	@echo "✅ Services stopped!"

restart:
	@echo "🔄 Restarting services..."
	docker-compose --profile multi-instance restart
	@echo "✅ Services restarted!"

logs:
	docker-compose --profile multi-instance logs -f instance-1

# Database Migration Commands
db.migrate: check.env
	@echo "🔄 Running Flyway migrations..."
	@export $$(cat .env 2>/dev/null | grep -v '^#' | xargs) && ./mvnw flyway:migrate
	@echo "✅ Migrations complete!"

db.info: check.env
	@echo "📊 Checking migration status..."
	@export $$(cat .env 2>/dev/null | grep -v '^#' | xargs) && ./mvnw flyway:info

db.clean: check.env
	@echo "⚠️  WARNING: This will drop all objects in the schema!"
	@read -p "Are you sure? [y/N] " -n 1 -r; \
	echo; \
	if [[ $$REPLY =~ ^[Yy]$$ ]]; then \
		export $$(cat .env 2>/dev/null | grep -v '^#' | xargs) && ./mvnw flyway:clean; \
		echo "✅ Schema cleaned!"; \
	else \
		echo "❌ Cancelled"; \
	fi

db.repair: check.env
	@echo "🔧 Repairing Flyway schema history..."
	@export $$(cat .env 2>/dev/null | grep -v '^#' | xargs) && ./mvnw flyway:repair

status:
	docker-compose ps

clean:
	@echo "🧹 Cleaning up stopped containers and volumes..."
	docker-compose down
	@echo "✅ Cleanup complete!"
