.PHONY: help test test.all test.watch test.components test.utils test.app test.file

.DEFAULT_GOAL := help

MAKEFILE_DIR := $(dir $(abspath $(lastword $(MAKEFILE_LIST))))
FRONTEND_ROOT := $(abspath $(MAKEFILE_DIR))
NPM := cd $(FRONTEND_ROOT) &&
TEST_ONCE := $(NPM) CI=true npm test -- --watchAll=false

help:
	@echo "Frontend Test Commands"
	@echo "======================"
	@echo ""
	@echo "Available targets:"
	@echo "  make test              - Run all frontend tests once (utils + components + App)"
	@echo "  make test.all          - Same as test"
	@echo "  make test.watch        - Run tests in watch mode (press a for all, q to quit)"
	@echo "  make test.components   - Run component tests only (src/components/**)"
	@echo "  make test.utils        - Run util tests only (src/utils/**)"
	@echo "  make test.app          - Run App smoke test only"
	@echo "  make test.file FILE=ImagePreview  - Run tests matching a filename pattern"
	@echo ""
	@echo "Examples:"
	@echo "  make test"
	@echo "  make test.components"
	@echo "  make test.file FILE=joinLinks"

test: test.all

test.all:
	@echo "🚀 Running all frontend tests..."
	@$(TEST_ONCE)

test.watch:
	@echo "🚀 Running frontend tests in watch mode (press a for all, q to quit)..."
	@$(NPM) npm test

test.components:
	@echo "🚀 Running component tests..."
	@$(TEST_ONCE) --testPathPattern=src/components/

test.utils:
	@echo "🚀 Running util tests..."
	@$(TEST_ONCE) --testPathPattern=src/utils/

test.app:
	@echo "🚀 Running App smoke test..."
	@$(TEST_ONCE) --testPathPattern=App\.test

test.file:
ifndef FILE
	@echo "❌ FILE is required. Example: make test.file FILE=ImagePreview"
	@exit 1
endif
	@echo "🚀 Running tests matching: $(FILE)"
	@$(TEST_ONCE) --testPathPattern=$(FILE)
