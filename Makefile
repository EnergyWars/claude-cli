.PHONY: generate-version generate-context-bundle dev build deploy release test lint format

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

release:
	npm run release

test:
	npm test

lint:
	npm run lint

format:
	npm run format
