all: build

build:
	lein uberjar
	docker build -t diogok/dwc-bot .

push:
	docker push diogok/dwc-bot

