// Copyright 2020 The Nomulus Authors. All Rights Reserved.
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

import com.google.common.collect.ImmutableList;
import google.registry.model.ImmutableObject;
import google.registry.persistence.transaction.JpaTestExtensions;
import google.registry.persistence.transaction.JpaTestExtensions.JpaUnitTestExtension;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NoResultException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Unit tests for {@code List<String>} conversion using Hibernate 6 builtin support. */
public class StringListConversionTest {

  @RegisterExtension
  public final JpaUnitTestExtension jpaExtension =
      new JpaTestExtensions.Builder().withEntityClass(TestEntity.class).buildUnitTestExtension();

  @Test
  void roundTripConversion_returnsSameStringList() {
    List<String> tlds = ImmutableList.of("app", "dev", "how");
    TestEntity testEntity = new TestEntity(tlds);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).containsExactly("app", "dev", "how");
  }

  @Test
  void testMerge_succeeds() {
    List<String> tlds = ImmutableList.of("app", "dev", "how");
    TestEntity testEntity = new TestEntity(tlds);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    persisted.tlds = ImmutableList.of("com", "gov");
    tm().transact(() -> tm().getEntityManager().merge(persisted));
    TestEntity updated = tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(updated.tlds).containsExactly("com", "gov");
  }

  @Test
  void testNullValue_writesAndReadsNullSuccessfully() {
    TestEntity testEntity = new TestEntity(null);
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).isNull();
  }

  @Test
  void testEmptyCollection_writesAndReadsEmptyCollectionSuccessfully() {
    TestEntity testEntity = new TestEntity(ImmutableList.of());
    persistResource(testEntity);
    TestEntity persisted =
        tm().transact(() -> tm().getEntityManager().find(TestEntity.class, "id"));
    assertThat(persisted.tlds).isEmpty();
  }

  @Test
  void testNativeQuery_succeeds() throws Exception {
    executeNativeQuery("INSERT INTO \"TestEntity\" (name, tlds) VALUES ('id', '{app, dev}')");

    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[1] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("app");
    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[2] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("dev");

    executeNativeQuery("UPDATE \"TestEntity\" SET tlds = '{com, gov}' WHERE name = 'id'");

    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[1] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("com");
    assertThat(
            getSingleResultFromNativeQuery("SELECT tlds[2] FROM \"TestEntity\" WHERE name = 'id'"))
        .isEqualTo("gov");

    executeNativeQuery("DELETE FROM \"TestEntity\" WHERE name = 'id'");
    assertThrows(
        NoResultException.class,
        () ->
            getSingleResultFromNativeQuery("SELECT tlds[1] FROM \"TestEntity\" WHERE name = 'id'"));
  }

  private static Object getSingleResultFromNativeQuery(String sql) {
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).getSingleResult());
  }

  private static Object executeNativeQuery(String sql) {
    return tm().transact(() -> tm().getEntityManager().createNativeQuery(sql).executeUpdate());
  }

  @Entity(name = "TestEntity") // Override entity name to avoid the nested class reference.
  private static class TestEntity extends ImmutableObject {

    @Id String name = "id";

    List<String> tlds;

    private TestEntity() {}

    private TestEntity(List<String> tlds) {
      this.tlds = tlds;
    }
  }
}
