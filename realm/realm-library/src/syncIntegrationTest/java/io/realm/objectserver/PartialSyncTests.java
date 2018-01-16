package io.realm.objectserver;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

import io.realm.DynamicRealm;
import io.realm.OrderedCollectionChangeSet;
import io.realm.Realm;
import io.realm.RealmList;
import io.realm.RealmResults;
import io.realm.StandardIntegrationTest;
import io.realm.SyncConfiguration;
import io.realm.SyncManager;
import io.realm.SyncUser;
import io.realm.entities.AllJavaTypes;
import io.realm.entities.AllTypes;
import io.realm.entities.Dog;
import io.realm.exceptions.RealmException;
import io.realm.objectserver.model.PartialSyncModule;
import io.realm.objectserver.model.PartialSyncObjectA;
import io.realm.objectserver.model.PartialSyncObjectB;
import io.realm.objectserver.utils.Constants;
import io.realm.objectserver.utils.UserFactory;
import io.realm.rule.RunTestInLooperThread;

import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public class PartialSyncTests extends StandardIntegrationTest {

    @Test
    @RunTestInLooperThread
    public void invalidQuery() {
        AtomicInteger callbacks = new AtomicInteger(0);
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        final SyncConfiguration partialSyncConfig = configurationFactory.createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .partialRealm()
                .build();

        final Realm realm = Realm.getInstance(partialSyncConfig);
        looperThread.closeAfterTest(realm);

        // Backlinks not yet supported: https://github.com/realm/realm-core/pull/2947
        RealmResults<AllJavaTypes> query = realm.where(AllJavaTypes.class).equalTo("objectParents.fieldString", "Foo").findAllAsync();
        query.addChangeListener((results, changeSet) -> {
            switch (callbacks.incrementAndGet()) {
                case 1:
                    assertEquals(OrderedCollectionChangeSet.State.INITIAL, changeSet.getState());
                    break;

                case 2:
                    assertEquals(OrderedCollectionChangeSet.State.ERROR, OrderedCollectionChangeSet.State.ERROR);
                    assertTrue(changeSet.getError() instanceof IllegalArgumentException);
                    Throwable iae = changeSet.getError();
                    assertTrue(iae.getMessage().contains("ERROR: realm::QueryParser: Key path resolution failed"));
                    looperThread.testComplete();
                    break;

                default:
                    fail("Unexpected state: " + changeSet.getState());
            }
        });
        looperThread.keepStrongReference(query);
    }

    // List queries are operating on data that are always up to date as data in a list will
    // always be fetched as part of another top-level subscription. Thus `remoteDataLoaded` is
    // always true and no queries on them can fail.
    @Test
    @RunTestInLooperThread
    public void listQueries_doNotCreateSubscriptions() {
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);
        final SyncConfiguration partialSyncConfig = configurationFactory.createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .partialRealm()
                .build();

        final DynamicRealm dRealm = DynamicRealm.getInstance(partialSyncConfig);
        final Realm realm = Realm.getInstance(partialSyncConfig);
        looperThread.closeAfterTest(dRealm);
        looperThread.closeAfterTest(realm);

        realm.beginTransaction();
        RealmList<Dog> list = realm.createObject(AllTypes.class).getColumnRealmList();
        list.add(new Dog("Fido"));
        list.add(new Dog("Eido"));
        realm.commitTransaction();

        RealmResults<Dog> query = list.where().sort("name").findAllAsync();
        query.addChangeListener((dogs, changeSet) -> {
            assertEquals(OrderedCollectionChangeSet.State.INITIAL, changeSet.getState());
            assertEquals(0, dRealm.where("__ResultSets").count());
            looperThread.testComplete();
        });
        looperThread.keepStrongReference(query);
    }

    @Test
    @RunTestInLooperThread
    public void partialSync() throws InterruptedException {
        SyncUser user = UserFactory.createUniqueUser(Constants.AUTH_URL);

        final SyncConfiguration syncConfig = configurationFactory.createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .waitForInitialRemoteData()
                .modules(new PartialSyncModule())
                .build();

        final SyncConfiguration partialSyncConfig = configurationFactory.createSyncConfigurationBuilder(user, Constants.SYNC_SERVER_URL)
                .name("partialSync")
                .modules(new PartialSyncModule())
                .partialRealm()
                .build();

        // Create server data
        Realm realm = Realm.getInstance(syncConfig);
        realm.beginTransaction();
        PartialSyncObjectA objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(0);
        objectA.setString("realm");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(1);
        objectA.setString("");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(2);
        objectA.setString("");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(3);
        objectA.setString("");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(4);
        objectA.setString("realm");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(5);
        objectA.setString("sync");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(6);
        objectA.setString("partial");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(7);
        objectA.setString("partial");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(8);
        objectA.setString("partial");
        objectA = realm.createObject(PartialSyncObjectA.class);
        objectA.setNumber(9);
        objectA.setString("partial");

        for (int i = 0; i < 10; i++) {
            realm.createObject(PartialSyncObjectB.class).setNumber(i);
        }
        realm.commitTransaction();
        SyncManager.getSession(syncConfig).uploadAllLocalChanges();
        realm.close();

        // Download data in partial Realm
        final Realm partialSyncRealm = Realm.getInstance(partialSyncConfig);
        looperThread.closeAfterTest(partialSyncRealm);
        assertTrue(partialSyncRealm.isEmpty());

        RealmResults<PartialSyncObjectA> results = partialSyncRealm.where(PartialSyncObjectA.class)
                .greaterThan("number", 5)
                .findAllAsync();
        looperThread.keepStrongReference(results);

        results.addChangeListener((partialSyncObjectAS, changeSet) -> {
            if (changeSet.isCompleteResult()) {
                if (results.size() == 4) {
                    for (PartialSyncObjectA object : results) {
                        assertThat(object.getNumber(), greaterThan(5));
                        assertEquals("partial", object.getString());
                    }
                    // make sure the Realm contains only PartialSyncObjectA
                    assertEquals(0, partialSyncRealm.where(PartialSyncObjectB.class).count());
                    looperThread.testComplete();
                }
            }
        });
    }

}
