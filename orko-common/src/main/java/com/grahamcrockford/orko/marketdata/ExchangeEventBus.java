package com.grahamcrockford.orko.marketdata;

import static com.grahamcrockford.orko.marketdata.MarketDataType.TICKER;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.grahamcrockford.orko.spi.TickerSpec;
import com.grahamcrockford.orko.util.SafelyDispose;

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;


@Singleton
class ExchangeEventBus implements ExchangeEventRegistry {

  private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeEventBus.class);

  private final ConcurrentMap<MarketDataSubscription, AtomicInteger> allSubscriptions = Maps.newConcurrentMap();
  private final Multimap<String, MarketDataSubscription> subscriptionsBySubscriber = MultimapBuilder.hashKeys().hashSetValues().build();

  @Deprecated
  private final Multimap<String, Disposable> tickerDisposables = MultimapBuilder.hashKeys().hashSetValues().build();

  private final StampedLock rwLock = new StampedLock();
  private final MarketDataSubscriptionManager marketDataSubscriptionManager;

  @Inject
  ExchangeEventBus(MarketDataSubscriptionManager marketDataSubscriptionManager) {
    this.marketDataSubscriptionManager = marketDataSubscriptionManager;
  }

  @Override
  public void changeSubscriptions(String subscriberId, Set<MarketDataSubscription> targetSubscriptions) {

    LOGGER.debug("Changing subscriptions for subscriber {} to {}", subscriberId, targetSubscriptions);

    long stamp = rwLock.writeLock();
    try {

      boolean updated = false;

      Set<MarketDataSubscription> currentForSubscriber = ImmutableSet.copyOf(subscriptionsBySubscriber.get(subscriberId));
      Set<MarketDataSubscription> toRemove = Sets.difference(currentForSubscriber, targetSubscriptions);
      Set<MarketDataSubscription> toAdd = Sets.difference(targetSubscriptions, currentForSubscriber);

      for (MarketDataSubscription sub : toRemove) {
        LOGGER.debug("... unsubscribing {}", sub);
        if (unsubscribe(subscriberId, sub)) {
          LOGGER.debug("   ... removing global subscription");
          updated = true;
        }
      }

      for (MarketDataSubscription sub : toAdd) {
        LOGGER.debug("... subscribing {}", sub);
        if (subscribe(subscriberId, sub)) {
          LOGGER.debug("   ... new global subscription");
          updated = true;
        }
      }

      if (updated) {
        updateSubscriptions();
      }

    } finally {
      rwLock.unlockWrite(stamp);
    }
  }

  @Override
  public Flowable<TickerEvent> getTickers(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return marketDataSubscriptionManager.getTickers()
        .filter(e -> subscriptions.contains(e.spec()))
        .onBackpressureLatest();
  }

  @Override
  public Iterable<Flowable<TickerEvent>> getTickersSplit(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return FluentIterable
        .from(subscriptions)
        .transform(spec -> marketDataSubscriptionManager.getTickers()
            .filter(e -> e.spec().equals(spec))
            .onBackpressureLatest());
  }

  @Override
  public Flowable<OpenOrdersEvent> getOpenOrders(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return marketDataSubscriptionManager.getOpenOrders()
        .filter(e -> subscriptions.contains(e.spec()))
        .onBackpressureLatest();
  }

  @Override
  public Flowable<OrderBookEvent> getOrderBooks(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return marketDataSubscriptionManager.getOrderBooks()
        .filter(e -> subscriptions.contains(e.spec()))
        .onBackpressureLatest();
  }

  @Override
  public Flowable<TradeEvent> getTrades(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return marketDataSubscriptionManager.getTrades()
        .filter(e -> subscriptions.contains(e.spec()))
        .onBackpressureLatest();
  }

  @Override
  public Flowable<TradeHistoryEvent> getUserTradeHistory(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return marketDataSubscriptionManager.getUserTradeHistory()
        .filter(e -> subscriptions.contains(e.spec()))
        .onBackpressureLatest();
  }

  @Override
  public Iterable<Flowable<TradeHistoryEvent>> getUserTradeHistorySplit(String subscriberId) {
    Set<TickerSpec> subscriptions = safeGetSubscriptions(subscriberId, TICKER);
    return FluentIterable
        .from(subscriptions)
        .transform(spec -> marketDataSubscriptionManager.getUserTradeHistory()
            .filter(e -> e.spec().equals(spec))
            .onBackpressureLatest());
  }

  @Override
  public Flowable<BalanceEvent> getBalance(String subscriberId) {
    ImmutableSet<String> currenciesSubscribed = FluentIterable.from(safeGetSubscriptions(subscriberId, TICKER))
      .transformAndConcat(s -> ImmutableSet.of(s.base(), s.counter()))
      .toSet();
    return marketDataSubscriptionManager.getBalances()
        .filter(e -> currenciesSubscribed.contains(e.currency()))
        .onBackpressureLatest();
  }

  @Override
  public void registerTicker(TickerSpec tickerSpec, String subscriberId, Consumer<TickerEvent> callback) {
    changeSubscriptions(subscriberId, MarketDataSubscription.create(tickerSpec, TICKER));
    Disposable disposable = getTickers(subscriberId).subscribe(e -> callback.accept(e));
    long stamp = rwLock.writeLock();
    try {
      tickerDisposables.put(subscriberId, disposable);
    } finally {
      rwLock.unlockWrite(stamp);
    }
  }

  @Override
  public void unregisterTicker(TickerSpec tickerSpec, String subscriberId) {
    long stamp = rwLock.writeLock();
    try {
      SafelyDispose.of(tickerDisposables.get(subscriberId));
      tickerDisposables.removeAll(subscriberId);
    } finally {
      rwLock.unlockWrite(stamp);
    }
    clearSubscriptions(subscriberId);
  }

  private Set<TickerSpec> safeGetSubscriptions(String subscriberId, MarketDataType marketDataType) {
    long stamp = rwLock.readLock();
    try {
      return FluentIterable.from(subscriptionsBySubscriber.get(subscriberId))
          .filter(s -> s.type().equals(marketDataType))
          .transform(MarketDataSubscription::spec)
          .toSet();
    } finally {
      rwLock.unlockRead(stamp);
    }
  }

  private <T> boolean subscribe(String subscriberId, MarketDataSubscription subscription) {
    if (subscriptionsBySubscriber.put(subscriberId, subscription)) {
      return allSubscriptions.computeIfAbsent(subscription, s -> new AtomicInteger(0)).incrementAndGet() == 1;
    } else {
      LOGGER.info("   ... subscriber already subscribed");
      return false;
    }
  }

  private <T> boolean unsubscribe(String subscriberId, MarketDataSubscription subscription) {
    if (subscriptionsBySubscriber.remove(subscriberId, subscription)) {
      AtomicInteger refCount = allSubscriptions.get(subscription);
      if (refCount == null) {
        LOGGER.warn("   ... Refcount is unset for live subscription: {}/{}", subscriberId, subscription);
        return true;
      }
      int newRefCount = refCount.decrementAndGet();
      LOGGER.debug("   ... refcount set to {}", newRefCount);
      if (newRefCount == 0) {
        allSubscriptions.remove(subscription);
        return true;
      } else {
        LOGGER.debug("   ... other subscribers still holding it open");
        return false;
      }
    } else {
      LOGGER.warn("   ... subscriber {} not actually subscribed to {}", subscriberId, subscription);
      return false;
    }
  }

  private void updateSubscriptions() {
    marketDataSubscriptionManager.updateSubscriptions(allSubscriptions.keySet());
  }
}