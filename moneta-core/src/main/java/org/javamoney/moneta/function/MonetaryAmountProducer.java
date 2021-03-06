/*
  Copyright (c) 2012, 2015, Credit Suisse (Anatole Tresch), Werner Keil and others by the @author tag.

  Licensed under the Apache License, Version 2.0 (the "License"); you may not
  use this file except in compliance with the License. You may obtain a copy of
  the License at

  http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  License for the specific language governing permissions and limitations under
  the License.
 */
package org.javamoney.moneta.function;

import javax.money.CurrencyUnit;
import javax.money.MonetaryAmount;

/**
 *The producer of {@link MonetaryAmount} from {@link CurrencyUnit} and {@link Number}
 * @author Otavio Santana
 * @see FastMoneyProducer
 * @see MoneyProducer
 * @see RoundedMoneyProducer
 * @since 1.0.1
 */
@FunctionalInterface
public interface MonetaryAmountProducer {
	/**
	 * Creates a {@link MonetaryAmount} from {@link CurrencyUnit} and {@link Number}
	 * @param currency the currency unit, not null.
	 * @param number the amount, not null.
	 * @return a {@link MonetaryAmount} never null
	 * @throws NullPointerException if currency and Number is null
	 */
	MonetaryAmount create(CurrencyUnit currency, Number number);

}
