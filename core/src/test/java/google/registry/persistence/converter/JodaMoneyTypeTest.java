// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package google.registry.persistence.converter;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableMap;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.PersistenceException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.joda.money.CurrencyUnit;
import org.joda.money.IllegalCurrencyException;
import org.joda.money.Money;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for embeddable {@link JodaMoneyType}. */
public class JodaMoneyTypeTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder()
          .withEntityClass(TestEntity.class, ComplexTestEntity.class)
          .buildUnitTestExtension();

  @Test
  void roundTripConversion() {
    Money money = Money.of(CurrencyUnit.USD, 100.12);
    assertThat(money.getAmount().scale()).isEqualTo(2);
    TestEntity entity = new TestEntity(money);
    persistResource(entity);
    List<?> result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result).hasSize(1);
    // The amount property, when loaded as a raw value, has the same scale as the table column,
    // which is 2.
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(100.12).setScale(2), "USD")
        .inOrder();
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.money).isEqualTo(money);
  }

  @Test
  void roundTripConversion_scale0() {
    Money money = Money.ofMajor(CurrencyUnit.JPY, 100);
    assertThat(money.getAmount().scale()).isEqualTo(0); // JPY's amount has scale at 0.
    TestEntity entity = new TestEntity(money);
    persistResource(entity);
    List<?> result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result).hasSize(1);
    /* The amount property, when loaded as a raw value, has the same scale as the table column,
    which is 2. */
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(100).setScale(2), "JPY")
        .inOrder();

    result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createQuery("SELECT money FROM TestEntity WHERE name" + " = 'id'")
                        .getResultList());

    // When the money field is loaded as an embedded entity, it has the desired scale (0).
    assertThat(result.get(0)).isEqualTo(money);

    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.money).isEqualTo(money);
  }

  @Test
  void roundTripConversionWithComplexEntity() {
    Money myMoney = Money.of(CurrencyUnit.USD, 100);
    Money yourMoney = Money.of(CurrencyUnit.GBP, 80);
    ImmutableMap<String, Money> moneyMap =
        ImmutableMap.of(
            "uno", Money.of(CurrencyUnit.EUR, 500),
            "dos", Money.ofMajor(CurrencyUnit.JPY, 2000),
            "tres", Money.of(CurrencyUnit.GBP, 20));
    ComplexTestEntity entity = new ComplexTestEntity(moneyMap, myMoney, yourMoney);
    persistResource(entity);
    List<?> result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT my_amount, my_currency, your_amount, your_currency FROM"
                                + " \"ComplexTestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result).hasSize(1);
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(
            BigDecimal.valueOf(100).setScale(2), "USD", BigDecimal.valueOf(80).setScale(2), "GBP")
        .inOrder();
    result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"MoneyMap\""
                                + " WHERE entity_name = 'id' AND map_key = 'dos'")
                        .getResultList());
    ComplexTestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(ComplexTestEntity.class, "id"));
    assertThat(result).hasSize(1);

    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(2000).setScale(2), "JPY")
        .inOrder();
    // Make sure that the loaded entity contains the fields exactly as they are persisted.
    assertThat(persisted.myMoney).isEqualTo(myMoney);
    assertThat(persisted.yourMoney).isEqualTo(yourMoney);
    assertThat(persisted.moneyMap).containsExactlyEntriesIn(moneyMap);
  }

  /**
   * Implicit test cases for @override method @nullSafeGet when constructing {@link Money} object
   * with null/invalid column(s).
   */
  @Test
  void testNullSafeGet_nullAmountNullCurrency_returnsNull() throws SQLException {
    tm().transact(
            () ->
                tm().getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"TestEntity\" (name, amount, currency) VALUES('id', null,"
                            + " null)")
                    .executeUpdate());
    List<?> result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result).hasSize(1);
    assertThat(Arrays.asList((Object[]) result.get(0))).containsExactly(null, null).inOrder();
    assertThat(
            tm().transact(
                    () ->
                        tm().getEntityManager()
                            .createQuery("SELECT money FROM TestEntity WHERE name = 'id'")
                            .getResultList())
                .get(0))
        .isNull();
  }

  @Test
  void testNullSafeGet_nullAMountValidCurrency_throwsHibernateException() throws SQLException {
    tm().transact(
            () ->
                tm().getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"TestEntity\" (name, amount, currency) VALUES('id', null,"
                            + " 'USD')")
                    .executeUpdate());
    List<?> result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(Arrays.asList((Object[]) result.get(0))).containsExactly(null, "USD");
    // CurrencyUnit.of() throws HibernateException for invalid currency which leads to persistance
    // error
    PersistenceException thrown =
        assertThrows(
            PersistenceException.class,
            () ->
                tm().transact(
                        () ->
                            tm().getEntityManager()
                                .createQuery("SELECT money FROM TestEntity WHERE name = 'id'")
                                .getResultList()));
    assertThat(thrown)
        .hasMessageThat()
        .isEqualTo("Mismatching null state between currency 'USD' and amount 'null'");
  }

  @Test
  void testNullSafeGet_nullAMountInValidCurrency_throwsIllegalCurrencyException()
      throws SQLException {
    tm().transact(
            () ->
                tm().getEntityManager()
                    .createNativeQuery(
                        "INSERT INTO \"TestEntity\" (name, amount, currency) VALUES('id', 100,"
                            + " 'INVALIDCURRENCY')")
                    .executeUpdate());
    List<?> result =
        tm().transact(
                () ->
                    tm().getEntityManager()
                        .createNativeQuery(
                            "SELECT amount, currency FROM \"TestEntity\" WHERE name = 'id'")
                        .getResultList());
    assertThat(result).hasSize(1);
    assertThat(Arrays.asList((Object[]) result.get(0)))
        .containsExactly(BigDecimal.valueOf(100).setScale(2), "INVALIDCURRENCY")
        .inOrder();
    IllegalCurrencyException thrown =
        assertThrows(
            IllegalCurrencyException.class,
            () ->
                tm().transact(
                        () ->
                            tm().getEntityManager()
                                .createQuery("SELECT money FROM TestEntity WHERE name = 'id'")
                                .getResultList()));
    assertThat(thrown).hasMessageThat().isEqualTo("Unknown currency 'INVALIDCURRENCY'");
  }

  // Override entity name to exclude outer-class name in table name. Not necessary if class is not
  // inner class.
  @Entity(name = "TestEntity")
  public static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    Money money;

    public TestEntity() {}

    TestEntity(Money money) {
      this.money = money;
    }
  }

  // See comments on the annotation for TestEntity above for reason.
  @Entity(name = "ComplexTestEntity")
  // This entity is used to test column override for embedded fields and collections.
  public static class ComplexTestEntity extends ImmutableObject {

    @Id String name = "id";

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "MoneyMap", joinColumns = @JoinColumn(name = "entity_name"))
    @MapKeyColumn(name = "map_key")
    Map<String, Money> moneyMap;

    @AttributeOverride(name = "amount", column = @Column(name = "my_amount"))
    @AttributeOverride(name = "currency", column = @Column(name = "my_currency"))
    Money myMoney;

    @AttributeOverride(name = "amount", column = @Column(name = "your_amount"))
    @AttributeOverride(name = "currency", column = @Column(name = "your_currency"))
    Money yourMoney;

    public ComplexTestEntity() {}

    ComplexTestEntity(ImmutableMap<String, Money> moneyMap, Money myMoney, Money yourMoney) {
      this.moneyMap = moneyMap;
      this.myMoney = myMoney;
      this.yourMoney = yourMoney;
    }
  }
}
