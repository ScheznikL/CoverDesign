import { readFile } from 'node:fs/promises';
import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, setDoc, updateDoc } from 'firebase/firestore';

let env;
const PATIENT = 'dev-patient';
const DOCTOR = 'dev-doctor';
function db(uid = PATIENT) { return env.authenticatedContext(uid).firestore(); }
function profile(uid = PATIENT, role = 'PATIENT') {
  return { uid, role, email: `${uid}@example.invalid`, displayName: 'Test Person', phone: '', createdAt: Date.now() - 10000 };
}
function userRef(store, uid = PATIENT) { return doc(store, 'users', uid); }
before(async () => {
  assert.equal(process.env.GAIT_RULES_FLAVOR, 'dev', 'Run npm run test:dev for the merged development suite.');
  const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? '').split(':');
  assert.equal(host, '127.0.0.1');
  env = await initializeTestEnvironment({ projectId: 'demo-coverdesign-gait', firestore: {
    host, port: Number(port), rules: await readFile(new URL('../firestore.dev.rules', import.meta.url), 'utf8'),
  } });
});
beforeEach(async () => { await env.clearFirestore(); });
after(async () => { if (env) await env.cleanup(); });

test('DEV: existing Auth account can read missing own profile and create PATIENT profile', async () => {
  const store = db();
  assert.equal((await assertSucceeds(getDoc(userRef(store)))).exists(), false);
  await assertSucceeds(setDoc(userRef(store), profile()));
  assert.equal((await assertSucceeds(getDoc(userRef(store)))).data().role, 'PATIENT');
});

test('DEV: own DOCTOR registration works, TECHNICIAN and arbitrary roles do not', async () => {
  await assertSucceeds(setDoc(userRef(db(DOCTOR), DOCTOR), profile(DOCTOR, 'DOCTOR')));
  for (const role of ['TECHNICIAN', 'ADMIN', 'doctor', '']) {
    const id = `new-${role || 'empty'}`;
    await assertFails(setDoc(userRef(db(id), id), profile(id, role)));
  }
});

test('DEV: contact edits and optional insoles work; identity, role and creation date are immutable', async () => {
  const store = db();
  const initial = profile();
  await setDoc(userRef(store), initial);
  await assertSucceeds(updateDoc(userRef(store), { displayName: 'Updated Name', phone: '+123', hasInsoles: true }));
  await assertFails(updateDoc(userRef(store), { uid: 'other-identity' }));
  await assertFails(updateDoc(userRef(store), { role: 'DOCTOR' }));
  await assertFails(updateDoc(userRef(store), { createdAt: initial.createdAt + 1000 }));
  await assertFails(updateDoc(userRef(store), { hasInsoles: 'yes' }));
  await assertFails(updateDoc(userRef(store), { admin: true }));
  await assertFails(deleteDoc(userRef(store)));
});

test('DEV: nobody can enumerate profiles or read/write another user profile, including doctors', async () => {
  await setDoc(userRef(db()), profile());
  await setDoc(userRef(db(DOCTOR), DOCTOR), profile(DOCTOR, 'DOCTOR'));
  for (const uid of [DOCTOR, 'another-patient']) {
    await assertFails(getDoc(userRef(db(uid))));
    await assertFails(setDoc(userRef(db(uid)), profile()));
    await assertFails(updateDoc(userRef(db(uid)), { role: 'DOCTOR' }));
    await assertFails(getDocs(collection(db(uid), 'users')));
  }
  await assertFails(getDocs(collection(db(), 'users')));
});

test('DEV: profile creation validates identity, required fields, bounded strings and timestamps', async () => {
  const invalid = [
    { ...profile(), uid: 'different-user' }, { ...profile(), createdAt: 0 },
    { ...profile(), createdAt: Date.now() + 3600000 }, { ...profile(), displayName: 'a'.repeat(121) },
    { ...profile(), phone: 123 }, { ...profile(), email: 'a'.repeat(321) },
    { ...profile(), admin: true },
  ];
  const missing = profile(); delete missing.role; invalid.push(missing);
  for (const data of invalid) await assertFails(setDoc(userRef(db()), data));
});

for (const kind of ['configs', 'orders']) test(`DEV: owner ${kind} CRUD works; other patients and doctors have no access`, async () => {
  const store = db();
  const target = doc(store, `users/${PATIENT}/${kind}/item-one`);
  await assertSucceeds(setDoc(target, { id: 'item-one', created_at: Date.now(), status: 'new' }));
  await assertSucceeds(getDoc(target));
  assert.equal((await assertSucceeds(getDocs(collection(store, `users/${PATIENT}/${kind}`)))).size, 1);
  await assertSucceeds(updateDoc(target, { status: 'updated' }));
  for (const uid of [DOCTOR, 'another-patient']) {
    const foreign = doc(db(uid), `users/${PATIENT}/${kind}/item-one`);
    await assertFails(getDoc(foreign));
    await assertFails(getDocs(collection(db(uid), `users/${PATIENT}/${kind}`)));
    await assertFails(setDoc(foreign, { id: 'forged' }));
    await assertFails(deleteDoc(foreign));
  }
  await assertSucceeds(deleteDoc(target));
});

test('DEV: unauthenticated users cannot access profiles, configurations or orders', async () => {
  const store = env.unauthenticatedContext().firestore();
  for (const path of [`users/${PATIENT}`, `users/${PATIENT}/configs/item`, `users/${PATIENT}/orders/item`]) {
    await assertFails(getDoc(doc(store, path)));
    await assertFails(setDoc(doc(store, path), path === `users/${PATIENT}` ? profile() : { id: 'item' }));
    await assertFails(deleteDoc(doc(store, path)));
  }
});
