/**
 * Copyright (c) 2012, 2014, Credit Suisse (Anatole Tresch), Werner Keil and others by the @author tag.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.javamoney.moneta.internal.convert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.ParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;

import javax.money.CurrencyContextBuilder;
import javax.money.CurrencyUnit;
import javax.money.Monetary;
import javax.money.convert.ConversionContext;
import javax.money.convert.ConversionContextBuilder;
import javax.money.convert.ConversionQuery;
import javax.money.convert.ExchangeRate;
import javax.money.convert.ExchangeRateProvider;
import javax.money.convert.ProviderContext;
import javax.money.convert.ProviderContextBuilder;
import javax.money.convert.RateType;
import javax.money.spi.Bootstrap;

import org.javamoney.moneta.CurrencyUnitBuilder;
import org.javamoney.moneta.ExchangeRateBuilder;
import org.javamoney.moneta.spi.AbstractRateProvider;
import org.javamoney.moneta.spi.DefaultNumberValue;
import org.javamoney.moneta.spi.LoaderService;
import org.javamoney.moneta.spi.LoaderService.LoaderListener;

/**
 * Implements a {@link ExchangeRateProvider} that loads the IMF conversion data.
 * In most cases this provider will provide chained rates, since IMF always is
 * converting from/to the IMF <i>SDR</i> currency unit.
 *
 * @author Anatole Tresch
 * @author Werner Keil
 */
public class IMFRateProvider extends AbstractRateProvider implements LoaderListener {

    /**
     * The data id used for the LoaderService.
     */
    private static final String DATA_ID = IMFRateProvider.class.getSimpleName();
    /**
     * The {@link ConversionContext} of this provider.
     */
    private static final ProviderContext CONTEXT = ProviderContextBuilder.of("IMF", RateType.DEFERRED)
            .set("providerDescription", "International Monetary Fond").set("days", 1).build();

    private static final CurrencyUnit SDR =
            CurrencyUnitBuilder.of("SDR", CurrencyContextBuilder.of(IMFRateProvider.class.getSimpleName()).build())
                    .setDefaultFractionDigits(3).build(true);

    private Map<CurrencyUnit, List<ExchangeRate>> currencyToSdr = new HashMap<>();

    private Map<CurrencyUnit, List<ExchangeRate>> sdrToCurrency = new HashMap<>();

    private static final Map<String, CurrencyUnit> CURRENCIES_BY_NAME = new HashMap<>();

    static {
        for (Currency currency : Currency.getAvailableCurrencies()) {
            CURRENCIES_BY_NAME.put(currency.getDisplayName(Locale.ENGLISH),
                    Monetary.getCurrency(currency.getCurrencyCode()));
        }
        // Additional IMF differing codes:
        // This mapping is required to fix data issues in the input stream, it has nothing to do with i18n
        CURRENCIES_BY_NAME.put("U.K. Pound Sterling", Monetary.getCurrency("GBP"));
        CURRENCIES_BY_NAME.put("U.S. Dollar", Monetary.getCurrency("USD"));
        CURRENCIES_BY_NAME.put("Bahrain Dinar", Monetary.getCurrency("BHD"));
        CURRENCIES_BY_NAME.put("Botswana Pula", Monetary.getCurrency("BWP"));
        CURRENCIES_BY_NAME.put("Czech Koruna", Monetary.getCurrency("CZK"));
        CURRENCIES_BY_NAME.put("Icelandic Krona", Monetary.getCurrency("ISK"));
        CURRENCIES_BY_NAME.put("Korean Won", Monetary.getCurrency("KRW"));
        CURRENCIES_BY_NAME.put("Rial Omani", Monetary.getCurrency("OMR"));
        CURRENCIES_BY_NAME.put("Nuevo Sol", Monetary.getCurrency("PEN"));
        CURRENCIES_BY_NAME.put("Qatar Riyal", Monetary.getCurrency("QAR"));
        CURRENCIES_BY_NAME.put("Saudi Arabian Riyal", Monetary.getCurrency("SAR"));
        CURRENCIES_BY_NAME.put("Sri Lanka Rupee", Monetary.getCurrency("LKR"));
        CURRENCIES_BY_NAME.put("Trinidad And Tobago Dollar", Monetary.getCurrency("TTD"));
        CURRENCIES_BY_NAME.put("U.A.E. Dirham", Monetary.getCurrency("AED"));
        CURRENCIES_BY_NAME.put("Peso Uruguayo", Monetary.getCurrency("UYU"));
        CURRENCIES_BY_NAME.put("Bolivar Fuerte", Monetary.getCurrency("VEF"));
    }

    public IMFRateProvider() {
        super(CONTEXT);
        LoaderService loader = Bootstrap.getService(LoaderService.class);
        loader.addLoaderListener(this, DATA_ID);
        try {
            loader.loadData(DATA_ID);
        } catch (IOException e) {
            log.log(Level.WARNING, "Error loading initial data from IMF provider...", e);
        }
    }

    @Override
    public void newDataLoaded(String resourceId, InputStream is) {
        try {
            loadRatesTSV(is);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error", e);
        }
    }

    @SuppressWarnings({"unchecked", "ConstantConditions"})
    private void loadRatesTSV(InputStream inputStream) throws IOException, ParseException {
        Map<CurrencyUnit, List<ExchangeRate>> newCurrencyToSdr = new HashMap<>();
        Map<CurrencyUnit, List<ExchangeRate>> newSdrToCurrency = new HashMap<>();
        NumberFormat f = new DecimalFormat("#0.0000000000");
        f.setGroupingUsed(false);
        BufferedReader pr = new BufferedReader(new InputStreamReader(inputStream));
        String line = pr.readLine();
        // int lineType = 0;
        boolean currencyToSdr = true;
        // SDRs per Currency unit (2)
        //
        // Currency January 31, 2013 January 30, 2013 January 29, 2013
        // January 28, 2013 January 25, 2013
        // Euro 0.8791080000 0.8789170000 0.8742470000 0.8752180000
        // 0.8768020000

        // Currency units per SDR(3)
        //
        // Currency January 31, 2013 January 30, 2013 January 29, 2013
        // January 28, 2013 January 25, 2013
        // Euro 1.137520 1.137760 1.143840 1.142570 1.140510
        List<LocalDate> timestamps = null;
        while (Objects.nonNull(line)) {
            if (line.trim().isEmpty()) {
                line = pr.readLine();
                continue;
            }
            if (line.startsWith("SDRs per Currency unit")) {
                currencyToSdr = false;
                line = pr.readLine();
                continue;
            } else if (line.startsWith("Currency units per SDR")) {
                currencyToSdr = true;
                line = pr.readLine();
                continue;
            } else if (line.startsWith("Currency")) {
                timestamps = readTimestamps(line);
                line = pr.readLine();
                continue;
            }
            String[] parts = line.split("\\t");
            CurrencyUnit currency = CURRENCIES_BY_NAME.get(parts[0]);
            if (Objects.isNull(currency)) {
                log.finest(() -> "Uninterpretable data from IMF data feed: " + parts[0]);
                line = pr.readLine();
                continue;
            }
            Double[] values = parseValues(f, parts);
            for (int i = 0; i < values.length; i++) {
                if (Objects.isNull(values[i])) {
                    continue;
                }
                LocalDate fromTS = timestamps != null ? timestamps.get(i) : null;
                if (fromTS == null) {
                    continue;
                }
                RateType rateType = RateType.HISTORIC;
                if (fromTS.equals(LocalDate.now())) {
                    rateType = RateType.DEFERRED;
                }
                if (currencyToSdr) { // Currency -> SDR
                    ExchangeRate rate = new ExchangeRateBuilder(
                            ConversionContextBuilder.create(CONTEXT, rateType).set(fromTS).build())
                            .setBase(currency).setTerm(SDR).setFactor(new DefaultNumberValue(1d / values[i])).build();
                    List<ExchangeRate> rates = newCurrencyToSdr.computeIfAbsent(currency, c -> new ArrayList<>(5));
                    rates.add(rate);
                } else { // SDR -> Currency
                    ExchangeRate rate = new ExchangeRateBuilder(
                            ConversionContextBuilder.create(CONTEXT, rateType).set(fromTS).build())
                            .setBase(SDR).setTerm(currency).setFactor(DefaultNumberValue.of(1d / values[i])).build();
                    List<ExchangeRate> rates = newSdrToCurrency.computeIfAbsent(currency, (c) -> new ArrayList<>(5));
                    rates.add(rate);
                }
            }
            line = pr.readLine();
        }
        // Cast is save, since contained DefaultExchangeRate is Comparable!
        newSdrToCurrency.values().forEach((c) -> Collections.sort(List.class.cast(c)));
        newCurrencyToSdr.values().forEach((c) -> Collections.sort(List.class.cast(c)));
        this.sdrToCurrency = newSdrToCurrency;
        this.currencyToSdr = newCurrencyToSdr;
        this.sdrToCurrency.forEach((c, l) -> log.finest(() -> "SDR -> " + c.getCurrencyCode() + ": " + l));
        this.currencyToSdr.forEach((c, l) -> log.finest(() -> c.getCurrencyCode() + " -> SDR: " + l));
    }

    private Double[] parseValues(NumberFormat f, String[] parts) throws ParseException {
        Double[] result = new Double[parts.length - 1];
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                continue;
            }
            result[i - 1] = f.parse(parts[i]).doubleValue();
        }
        return result;
    }

    private List<LocalDate> readTimestamps(String line) {
        // Currency May 01, 2013 April 30, 2013 April 29, 2013 April 26, 2013
        // April 25, 2013
        DateTimeFormatter sdf = DateTimeFormatter.ofPattern("MMMM dd, uuuu").withLocale(Locale.ENGLISH);
        String[] parts = line.split("\\\t");
        List<LocalDate> dates = new ArrayList<>(parts.length);
        for (int i = 1; i < parts.length; i++) {
            dates.add(LocalDate.parse(parts[i], sdf));
        }
        return dates;
    }

    @Override
    public ExchangeRate getExchangeRate(ConversionQuery conversionQuery) {
        if (!isAvailable(conversionQuery)) {
            return null;
        }
        CurrencyUnit base = conversionQuery.getBaseCurrency();
        CurrencyUnit term = conversionQuery.getCurrency();
        LocalDate timestamp = conversionQuery.get(LocalDate.class);
        if (timestamp == null) {
            LocalDateTime dateTime = conversionQuery.get(LocalDateTime.class);
            if (dateTime != null) {
                timestamp = dateTime.toLocalDate();
            }
        }
        ExchangeRate rate1 = lookupRate(currencyToSdr.get(base), timestamp);
        ExchangeRate rate2 = lookupRate(sdrToCurrency.get(term), timestamp);
        if (base.equals(SDR)) {
            return rate2;
        } else if (term.equals(SDR)) {
            return rate1;
        }
        if (Objects.isNull(rate1) || Objects.isNull(rate2)) {
            return null;
        }
        ExchangeRateBuilder builder =
                new ExchangeRateBuilder(ConversionContext.of(CONTEXT.getProviderName(), RateType.HISTORIC));
        builder.setBase(base);
        builder.setTerm(term);
        builder.setFactor(multiply(rate1.getFactor(), rate2.getFactor()));
        builder.setRateChain(rate1, rate2);
        return builder.build();
    }

    private ExchangeRate lookupRate(List<ExchangeRate> list, LocalDate localDate) {
        if (Objects.isNull(list)) {
            return null;
        }
        ExchangeRate found = null;
        for (ExchangeRate rate : list) {
            if (Objects.isNull(localDate)) {
                localDate = LocalDate.now();
            }
            if (isValid(rate.getContext(), localDate)) {
                return rate;
            }
            if (Objects.isNull(found)) {
                found = rate;
            }
        }
        return found;
    }

    private boolean isValid(ConversionContext conversionContext, LocalDate timestamp) {
        LocalDate validAt = conversionContext.get(LocalDate.class);
        //noinspection ConstantConditions
        return !(Objects.nonNull(validAt)) && validAt.equals(timestamp);
    }

}
