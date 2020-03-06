# Akka persistence gcp datastore example
[![CircleCI](https://circleci.com/gh/innFactory/akka-persistence-gcp-datastore-example.svg?style=svg&circle-token=e94b6445a0d9cabc3b0d301eec13ec96b32a3968)](https://circleci.com/gh/innFactory/akka-persistence-gcp-datastore-example)

This example is based on Lightbend [CQRS-SAMPLE](https://developer.lightbend.com/start/?group=akka&project=akka-samples-cqrs-scala)

This example contains a sample illustrating an CQRS design with [Akka Cluster Sharding](https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html), [Akka Cluster Singleton](https://doc.akka.io/docs/akka/2.6/typed/cluster-singleton.html), [Akka Persistence](https://doc.akka.io/docs/akka/2.6/typed/persistence.html) and [Akka Persistence Query](https://doc.akka.io/docs/akka/2.6/persistence-query.html).

## Overview

This sample application implements a CQRS-ES design that will side-effect in the read model on selected events persisted to google cloud datastore by the write model. In this sample, the side-effect is logging a line. A more practical example would be to send a message to a Kafka topic or update a relational database.

## Write model

The write model is a shopping cart.

The implementation is based on a sharded actor: each `ShoppingCart` is an [Akka Cluster Sharding](https://doc.akka.io/docs/akka/2.6/typed/cluster-sharding.html) entity. The entity actor `ShoppingCart` is an [EventSourcedBehavior](https://doc.akka.io/docs/akka/2.6/typed/persistence.html).

Events from the shopping carts are tagged and consumed by the read model.

## Read model

The read model is implemented in such a way that 'load' is sharded over a number of processors. This number is `event-processor.parallelism`.

The implementation is resilient: it uses an *Akka Cluster Singleton* in combination with *Akka Cluster Sharding* to always keep the event processors alive.

## Credits
[innFactory GmbH](https://innfactory.de)
