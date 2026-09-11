.PHONY: help check.env run test

.DEFAULT_GOAL := help

# Check and create .env file if it doesn't exist
check.env:
	@if [ ! -f .env ]; then \
		echo "📝 Creating .env file from example..."; \
		cp .env.example .env 2>/dev/null || echo "⚠️  .env.example not found, using defaults"; \
	fi

# Source .env instead of xargs+export — empty xargs output would re-export the
# whole shell environment and break on values containing quotes.
LOAD_ENV = set -a && . ./.env && set +a

help:
	@echo "Media Processing Service"
	@echo "======================="
	@echo ""
	@echo "Available targets:"
	@echo "  make run         - Run the Micronaut worker with .env variables loaded"
	@echo "  make test        - Run unit tests"
	@echo "  make help        - Show this help message"
	@echo ""
	@echo "First run copies .env.example to .env if .env is missing."
	@echo "Keep MEDIA_PROCESSING_CALLBACK_TOKEN in sync with chat-app-backend."

run: check.env
	@echo "🚀 Running media-processing locally with .env variables..."
	@$(LOAD_ENV) && ./mvnw mn:run

test:
	@echo "🚀 Running media-processing tests..."
	./mvnw test
