FROM diogok/java8

ENV PORT 80
WORKDIR /opt
RUN mkdir -p /var/data/dwc-bot
CMD ["java","-server","-DDATA_DIR=/var/data/dwc-bot","-XX:+UseConcMarkSweepGC","-XX:+UseCompressedOops","-XX:+DoEscapeAnalysis","-jar","dwc-bot.jar"]
VOLUME /var/data/dwc-bot

ADD target/dwc-bot-0.0.2-standalone.jar /opt/dwc-bot.jar

