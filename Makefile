all: build

build:
	lein uberjar
	docker build -t diogok/dwc-services .

push:
	docker push diogok/dwc-services

