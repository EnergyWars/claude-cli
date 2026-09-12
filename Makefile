.PHONY: generate-version generate-context-bundle dev build deploy deploy-service release release-service test lint format

generate-version:
	npm run generate-version

generate-context-bundle:
	npm run generate-context-bundle

dev:
	npm run dev

build:
	npm run build

deploy:
	npm run deploy

deploy-service:
	npm run deploy-service

release:
	npm run release

release-service:
	npm run release-service

test:
	npm test

lint:
	npm run lint

format:
	npm run format
