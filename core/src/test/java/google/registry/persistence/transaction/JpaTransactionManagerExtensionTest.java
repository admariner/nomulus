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

package google.registry.persistence.transaction;

import static com.google.common.truth.Truth.assertThat;
import static google.registry.persistence.transaction.TransactionManagerFactory.replicaTm;
import static google.registry.persistence.transaction.TransactionManagerFactory.tm;
import static google.registry.testing.DatabaseHelper.persistResource;
import static org.junit.jupiter.api.Assertions.assertThrows;

import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PersistenceException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * JUnit test for {@link JpaTransactionManagerExtension}, with {@link JpaUnitTestExtension} as
 * proxy.
 */
public class JpaTransactionManagerExtensionTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void verifiesExtensionWorks() {
    assertThrows(
        PersistenceException.class,
        () ->
            tm().transact(
                    () ->
                        tm().getEntityManager()
                            .createNativeQuery("SELECT * FROM NoneExistentTable")
                            .getResultList()));
    tm().transact(
            () -> {
              List<?> results =
                  tm().getEntityManager()
                      .createNativeQuery("SELECT * FROM \"TestEntity\"")
                      .getResultList();
              assertThat(results).isEmpty();
            });
  }

  @Test
  void testReplicaJpaTm() {
    TestEntity testEntity = new TestEntity("foo", "bar");
    assertThat(
            assertThrows(
                    PersistenceException.class,
                    () -> replicaTm().transact(() -> replicaTm().put(testEntity)))
                .getCause())
        .hasMessageThat()
        .isEqualTo("Error while committing the transaction");
  }

  @Test
  void testExtraParameters() {
    // This test verifies that 1) withEntityClass() has registered TestEntity and 2) The table
    // has been created, implying withProperty(HBM2DDL_AUTO, "update") worked.
    TestEntity original = new TestEntity("key", "value");
    persistResource(original);
    TestEntity retrieved =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "key"));
    assertThat(retrieved).isEqualTo(original);
  }

  @Entity(name = "TestEntity") // Specify name to avoid nested class naming issues.
  static class TestEntity extends ImmutableObject {
    @Id String key;
    String value;

    TestEntity(String key, String value) {
      this.key = key;
      this.value = value;
    }

    TestEntity() {}
  }
}
