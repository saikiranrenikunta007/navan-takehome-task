will .PHONY: help setup build test run up down logs demo clean

BASE_URL ?= http://localhost:8080
FIXTURES := task-a-receipt-autoitemize/fixtures/task-a

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-12s %s\n", $$1, $$2}'

build: ## Compile and package (skip tests)
	./mvnw -q clean package -DskipTests

test: ## Run the full test suite (one command)
	./mvnw test

run: ## Run the app locally (requires MySQL; use 'make up' first)
	./mvnw spring-boot:run

up: ## Start MySQL + OTel collector + app via docker compose
	docker compose up --build -d

down: ## Stop and remove containers
	docker compose down

logs: ## Tail app logs
	docker compose logs -f app

clean: ## Remove build artifacts and local receipt files
	./mvnw -q clean
	rm -rf data/receipts

demo: ## Run an end-to-end curl workflow against a running API
	@bash scripts/demo.sh "$(BASE_URL)" "$(FIXTURES)"
