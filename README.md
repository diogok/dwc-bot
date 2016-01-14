# dwc-bot

A bot to read DarwinCore Archives from IPTs, listening to changes with the RSS from IPT, into an SQLite database with Fulltext search, and make them available as an API.

## Deploy

You can generate the jar and run that, like:

    $ java -jar dwc-bot.jar
    $ java -DDATA_DIR=/var/data/dwc-bot -jar dwc-bot.jar # to specify where to save data

Or run with docker (recommended):

    $ docker run -d -p 8383:8383 -volume /var/data/dwc-bot:/var/data/dwc-bot:rw diogok/dwc-bot

Or with docker-compose:

```yaml
dwc-bot:
  image: diogok/dwc-bot
  ports:
    - 8383:8383
  volumes:
    - /var/data/dwc-bot:/var/data/dwc-bot:rw
```

## Dev

Install leningen, the tasks are:

    $ lein run # to run the server, with code reload
    $ lein midje # for tests
    $ lein uberjar # generate the deploy artifact
    $ docker build -t dwc-bot . # build the docker image

## Some numbers

Final run in SQLite with Fulltext Search:

- 113 resources (circa 635MB zip)
- 490000+ occurrences
- 30 minutes
- 8.7 GB

Concluding:

- 1.8KB per occurrence
- 2.700 occurrences per second
- 15x the size of the DarwinCore Zip
- 1.5x the size of the DarwinCore CSV

## License

MIT

