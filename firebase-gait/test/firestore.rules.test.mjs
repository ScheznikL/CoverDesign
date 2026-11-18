import { readFile } from 'node:fs/promises';
import { after, before, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, deleteDoc, doc, getDoc, getDocs, query, setDoc, updateDoc, where, writeBatch } from 'firebase/firestore';

const PROJECT = 'demo-coverdesign-gait';
const CODE = 'A'.repeat(32);
const OTHER_CODE = 'B'.repeat(32);
const uid = { patient: 'patient-one', otherPatient: 'patient-two', doctor: 'doctor-one', otherDoctor: 'doctor-two' };
let env;
function db(user = uid.patient) { return env.authenticatedContext(user).firestore(); }
function reportRef(store, patient = uid.patient, session = 'session-one') {
  return doc(store, `gaitReports/${patient}/sessions/${session}`);
}
function reports(store, patient = uid.patient) { return collection(store, `gaitReports/${patient}/sessions`); }
function chunkRef(store, index = '0', patient = uid.patient, session = 'session-one') {
  return doc(store, `gaitReports/${patient}/sessions/${session}/details/${index}`);
}
function chunks(store, patient = uid.patient) {
  return collection(store, `gaitReports/${patient}/sessions/session-one/details`);
}
function manifest(overrides = {}) {
  return { formatVersion: 1, chunkCount: 1, sha256: 'a'.repeat(64), compressedBytes: 3, uncompressedBytes: 3, ...overrides };
}
function chunk(index = 0, payload = 'YWJj') { return { index, payload }; }
function shareRef(store, patient = uid.patient) { return doc(store, 'gaitShares', patient); }
function codeRef(store, code = CODE) { return doc(store, 'gaitDoctorCodes', code); }
function invitation(doctorUid = uid.doctor, doctorDisplayName = 'Doctor One') {
  return { doctorUid, doctorDisplayName, createdAt: Date.now() - 10000 };
}
function share(overrides = {}) {
  return { patientUid: uid.patient, doctorUid: uid.doctor, doctorCode: CODE,
    doctorDisplayName: 'Doctor One', patientDisplayName: 'Patient One', active: true, updatedAt: Date.now(), ...overrides };
}
function report(overrides = {}) {
  return { schemaVersion: 1, sessionId: 'session-one', patientUid: uid.patient, createdAt: Date.now() - 10000,
    measurementMethod: 'POSE', acquisitionSource: 'LIVE', protocolVersion: 9, completed: true, manualLevel: false,
    comparisonGroupId: 'c'.repeat(64),
    typicalInclinations: { FRONT_SHOULDERS: 1.9, FRONT_PELVIS: 1.9, FRONT_KNEES: 6.9, BACK_SHOULDERS: 2.6, BACK_PELVIS: 2.1 },
    sampleCounts: { FRONT_SHOULDERS: 30, FRONT_PELVIS: 30, FRONT_KNEES: 30, BACK_SHOULDERS: 30, BACK_PELVIS: 30 },
    qualifiedMetrics: ['FRONT_SHOULDERS', 'FRONT_PELVIS', 'FRONT_KNEES', 'BACK_SHOULDERS', 'BACK_PELVIS'],
    acceptedFrames: 60, rejectedFrames: 3, frontValidDurationMs: 6000, backValidDurationMs: 6000, ...overrides };
}
async function seedReport() {
  await env.withSecurityRulesDisabled(async context => { await setDoc(reportRef(context.firestore()), report()); });
}
async function link() { await setDoc(shareRef(db()), share()); }

before(async () => {
  assert.ok(process.env.FIRESTORE_EMULATOR_HOST, 'Run npm test so every request targets the local emulator.');
  const [host, port] = process.env.FIRESTORE_EMULATOR_HOST.split(':');
  assert.equal(host, '127.0.0.1');
  env = await initializeTestEnvironment({ projectId: PROJECT,
    firestore: { host, port: Number(port), rules: await readFile(new URL(`../${process.env.GAIT_RULES_FILE ?? 'firestore.gait.rules'}`, import.meta.url), 'utf8') } });
});
beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async context => {
    const store = context.firestore();
    await Promise.all([
      ...Object.values(uid).map(id => setDoc(doc(store, 'users', id), {
        uid: id, role: id.startsWith('doctor') ? 'DOCTOR' : 'PATIENT', displayName: id,
      })),
      setDoc(doc(store, 'users', 'technician'), { uid: 'technician', role: 'TECHNICIAN' }),
      setDoc(codeRef(store), invitation()),
      setDoc(codeRef(store, OTHER_CODE), invitation(uid.otherDoctor, 'Doctor Two')),
    ]);
  });
});
after(async () => { if (env) await env.cleanup(); });

test('unauthenticated users cannot get codes, shares, reports or write results', async () => {
  await seedReport();
  const store = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(codeRef(store)));
  await assertFails(getDoc(shareRef(store)));
  await assertFails(getDoc(reportRef(store)));
  await assertFails(setDoc(reportRef(store), report()));
});

test('patient owner can create, get, list, update and delete their compact report', async () => {
  const store = db();
  const data = report();
  await assertSucceeds(setDoc(reportRef(store), data));
  assert.equal((await assertSucceeds(getDoc(reportRef(store)))).data().typicalInclinations.FRONT_KNEES, 6.9);
  assert.equal((await assertSucceeds(getDocs(reports(store)))).size, 1);
  await assertSucceeds(updateDoc(reportRef(store), { rejectedFrames: 5 }));
  await assertSucceeds(deleteDoc(reportRef(store)));
});

test('another patient and doctor role alone have no access to report or session list', async () => {
  await seedReport();
  for (const user of [uid.otherPatient, uid.doctor, uid.otherDoctor, 'technician', 'missing-profile']) {
    await assertFails(getDoc(reportRef(db(user))));
    await assertFails(getDocs(reports(db(user))));
    await assertFails(setDoc(reportRef(db(user)), report()));
    await assertFails(deleteDoc(reportRef(db(user))));
  }
});

test('known-code get works for patients and owner doctor; code enumeration is forbidden', async () => {
  await assertSucceeds(getDoc(codeRef(db())));
  await assertSucceeds(getDoc(codeRef(db(uid.doctor))));
  await assertFails(getDoc(codeRef(db(uid.otherDoctor))));
  await assertFails(getDoc(codeRef(db('technician'))));
  for (const user of [uid.patient, uid.doctor]) {
    await assertFails(getDocs(collection(db(user), 'gaitDoctorCodes')));
    await assertFails(getDocs(query(collection(db(user), 'gaitDoctorCodes'), where('doctorUid', '==', uid.doctor))));
  }
});

test('only doctor can create their own code, every invitation field is immutable', async () => {
  const newCode = 'C'.repeat(32);
  await assertFails(setDoc(codeRef(db(), newCode), invitation()));
  await assertFails(setDoc(codeRef(db(uid.otherDoctor), newCode), invitation()));
  await assertFails(setDoc(codeRef(db(uid.doctor), 'short-code'), invitation()));
  await assertSucceeds(setDoc(codeRef(db(uid.doctor), newCode), invitation()));
  await assertFails(updateDoc(codeRef(db(uid.doctor), newCode), { doctorDisplayName: 'Changed' }));
  await assertFails(updateDoc(codeRef(db(uid.doctor), newCode), { doctorUid: uid.otherDoctor }));
  await assertFails(deleteDoc(codeRef(db(uid.otherDoctor), newCode)));
  await assertSucceeds(deleteDoc(codeRef(db(uid.doctor), newCode)));
});

test('patient explicitly links valid doctor; doctor can read only linked patient results', async () => {
  await seedReport();
  await assertSucceeds(setDoc(shareRef(db()), share()));
  await assertSucceeds(getDoc(shareRef(db())));
  await assertSucceeds(getDoc(shareRef(db(uid.doctor))));
  await assertSucceeds(getDoc(reportRef(db(uid.doctor))));
  await assertSucceeds(getDocs(reports(db(uid.doctor))));
  await assertFails(getDoc(reportRef(db(uid.otherDoctor))));
  await assertFails(getDoc(reportRef(db(uid.otherPatient))));
  await assertFails(setDoc(reportRef(db(uid.doctor)), report()));
  await assertFails(deleteDoc(reportRef(db(uid.doctor))));
  await assertFails(updateDoc(shareRef(db(uid.doctor)), { active: false }));
  await assertFails(deleteDoc(shareRef(db(uid.doctor))));
});

test('doctor list requires doctorUid and active filters, never exposes other doctors patients', async () => {
  await link();
  await setDoc(shareRef(db(uid.otherPatient), uid.otherPatient), share({ patientUid: uid.otherPatient,
    doctorUid: uid.otherDoctor, doctorCode: OTHER_CODE, doctorDisplayName: 'Doctor Two' }));
  const store = db(uid.doctor);
  const constrained = query(collection(store, 'gaitShares'), where('doctorUid', '==', uid.doctor), where('active', '==', true));
  const result = await assertSucceeds(getDocs(constrained));
  assert.deepEqual(result.docs.map(item => item.id), [uid.patient]);
  await assertFails(getDocs(collection(store, 'gaitShares')));
  await assertFails(getDocs(query(collection(store, 'gaitShares'), where('doctorUid', '==', uid.doctor))));
  await assertFails(getDocs(query(collection(store, 'gaitShares'), where('active', '==', true))));
  await assertFails(getDocs(query(collection(db(), 'gaitShares'), where('patientUid', '==', uid.patient))));
});

test('link rejects forged patient, missing invite, wrong doctor, wrong name and extra fields', async () => {
  for (const changes of [
    { patientUid: uid.otherPatient }, { doctorUid: uid.otherDoctor }, { doctorCode: 'D'.repeat(32) },
    { doctorDisplayName: 'Fake Name' }, { doctorCode: 'invalid-code' }, { active: 'true' },
    { video: 'forbidden' }, { doctorUid: uid.patient },
  ]) await assertFails(setDoc(shareRef(db()), share(changes)));
  await assertFails(setDoc(shareRef(db(uid.otherPatient)), share()));
  await assertFails(setDoc(shareRef(db(uid.doctor)), share()));
});

test('setting active false revokes doctor get/list access; patient retains data', async () => {
  await seedReport(); await link();
  await assertSucceeds(updateDoc(shareRef(db()), { active: false, updatedAt: Date.now() }));
  await assertFails(getDoc(shareRef(db(uid.doctor))));
  await assertFails(getDoc(reportRef(db(uid.doctor))));
  await assertFails(getDocs(reports(db(uid.doctor))));
  await assertSucceeds(getDoc(reportRef(db())));
  const result = await assertSucceeds(getDocs(query(collection(db(uid.doctor), 'gaitShares'),
    where('doctorUid', '==', uid.doctor), where('active', '==', true))));
  assert.equal(result.size, 0);
});

test('deleting patient share also revokes doctor access', async () => {
  await seedReport(); await link();
  await assertSucceeds(deleteDoc(shareRef(db())));
  await assertFails(getDoc(reportRef(db(uid.doctor))));
});

test('deleting invitation or losing doctor role removes access; patient may still revoke', async () => {
  await seedReport(); await link();
  await assertSucceeds(deleteDoc(codeRef(db(uid.doctor))));
  await assertFails(getDoc(reportRef(db(uid.doctor))));
  await assertSucceeds(updateDoc(shareRef(db()), { active: false, updatedAt: Date.now() }));
  await assertFails(updateDoc(shareRef(db()), { active: true, updatedAt: Date.now() }));
  await env.withSecurityRulesDisabled(async context => {
    await setDoc(codeRef(context.firestore()), invitation());
    await updateDoc(doc(context.firestore(), 'users', uid.doctor), { role: 'PATIENT' });
    await updateDoc(shareRef(context.firestore()), { active: true });
  });
  await assertFails(getDoc(reportRef(db(uid.doctor))));
});

test('markers allow measured BACK_SPINE; pose and marker reports keep absent metrics absent', async () => {
  const data = report({ measurementMethod: 'MARKERS', protocolVersion: 7 });
  data.typicalInclinations.BACK_SPINE = 3.4;
  data.sampleCounts.BACK_SPINE = 30;
  data.qualifiedMetrics.push('BACK_SPINE');
  await assertSucceeds(setDoc(reportRef(db()), data));
  await assertSucceeds(updateDoc(reportRef(db()), { typicalInclinations: { FRONT_KNEES: 0 },
    sampleCounts: { FRONT_KNEES: 3 }, qualifiedMetrics: [], completed: false }));
});

test('draft can have no measured metrics without inventing zero angles', async () => {
  await assertSucceeds(setDoc(reportRef(db()), report({ typicalInclinations: {}, sampleCounts: {}, qualifiedMetrics: [],
    completed: false, acceptedFrames: 0, frontValidDurationMs: 0, backValidDurationMs: 0 })));
});

const malformed = [
  ['raw samples', data => { data.samples = [{ angles: {} }]; }],
  ['raw video', data => { data.videoUrl = 'https://example.invalid/video'; }],
  ['raw demographics', data => { data.profile = { age: 30 }; }],
  ['missing field', data => { delete data.sampleCounts; }],
  ['cross-patient uid', data => { data.patientUid = uid.otherPatient; }],
  ['cross-session id', data => { data.sessionId = 'other-session'; }],
  ['schema type', data => { data.schemaVersion = '1'; }],
  ['method', data => { data.measurementMethod = 'UNKNOWN'; }],
  ['source', data => { data.acquisitionSource = 'UNKNOWN'; }],
  ['protocol', data => { data.protocolVersion = 0; }],
  ['unsupported protocol', data => { data.protocolVersion = 101; }],
  ['completion type', data => { data.completed = 'true'; }],
  ['level type', data => { data.manualLevel = 0; }],
  ['timestamp type', data => { data.createdAt = 'today'; }],
  ['future timestamp', data => { data.createdAt = Date.now() + 3600000; }],
  ['negative frames', data => { data.acceptedFrames = -1; }],
  ['float frames', data => { data.acceptedFrames = 60.5; }],
  ['negative duration', data => { data.frontValidDurationMs = -1; }],
  ['empty comparison group', data => { data.comparisonGroupId = ''; }],
  ['non-hash comparison group', data => { data.comparisonGroupId = 'opaque-settings-fingerprint'; }],
  ['unknown angle key', data => { data.typicalInclinations.BACK_KNEES = 4; data.sampleCounts.BACK_KNEES = 5; }],
  ['negative angle', data => { data.typicalInclinations.FRONT_KNEES = -1; }],
  ['out-of-range angle', data => { data.typicalInclinations.FRONT_KNEES = 91; }],
  ['NaN angle', data => { data.typicalInclinations.FRONT_KNEES = NaN; }],
  ['infinite angle', data => { data.typicalInclinations.FRONT_KNEES = Infinity; }],
  ['string angle', data => { data.typicalInclinations.FRONT_KNEES = '6.9'; }],
  ['count key mismatch', data => { delete data.sampleCounts.FRONT_KNEES; }],
  ['nonpositive sample count', data => { data.sampleCounts.FRONT_KNEES = 0; }],
  ['samples exceed accepted', data => { data.sampleCounts.FRONT_KNEES = 61; }],
  ['samples exceed client limit', data => { data.acceptedFrames = 40000; data.sampleCounts.FRONT_KNEES = 30001; }],
  ['qualified metric absent', data => { data.qualifiedMetrics.push('BACK_SPINE'); }],
  ['qualified duplicate', data => { data.qualifiedMetrics.push('FRONT_KNEES'); }],
  ['pose spine', data => { data.typicalInclinations.BACK_SPINE = 1; data.sampleCounts.BACK_SPINE = 30; }],
];
for (const [label, mutate] of malformed) test(`rejects malformed report: ${label}`, async () => {
  const data = report(); mutate(data);
  await assertFails(setDoc(reportRef(db()), data));
});

test('report creation timestamp stays immutable when session is updated', async () => {
  await setDoc(reportRef(db()), report());
  await assertFails(updateDoc(reportRef(db()), { createdAt: Date.now() - 20000 }));
});

test('gait access grants no unrelated cross-user profile, order, config or raw media permissions', async () => {
  const target = process.env.GAIT_RULES_FLAVOR === 'dev' ? uid.otherPatient : uid.patient;
  for (const path of [`users/${target}`, `users/${target}/orders/order-one`,
    `users/${target}/configs/config-one`, 'sessions/raw-video']) {
    await assertFails(getDoc(doc(db(), path)));
    await assertFails(setDoc(doc(db(), path), { arbitrary: true }));
  }
});

test('detail manifest remains optional for schema 1 and valid detail manifests can be added', async () => {
  const data = report();
  await assertSucceeds(setDoc(reportRef(db()), data));
  await assertSucceeds(updateDoc(reportRef(db()), { detailManifest: manifest() }));
  assert.equal((await getDoc(reportRef(db()))).data().schemaVersion, 1);
  await assertSucceeds(setDoc(reportRef(db()), data));
});

test('atomic new parent and detail chunk writes use the post-batch manifest', async () => {
  const store = db();
  const batch = writeBatch(store);
  batch.set(reportRef(store), report({ detailManifest: manifest({ chunkCount: 2 }) }));
  batch.set(chunkRef(store), chunk());
  batch.set(chunkRef(store, '1'), chunk(1));
  await assertSucceeds(batch.commit());
  assert.equal((await getDocs(chunks(store))).size, 2);
});

test('details cannot be created without parent manifest or beyond its chunk count', async () => {
  await assertFails(setDoc(chunkRef(db()), chunk()));
  await setDoc(reportRef(db()), report());
  await assertFails(setDoc(chunkRef(db()), chunk()));
  await updateDoc(reportRef(db()), { detailManifest: manifest() });
  await assertSucceeds(setDoc(chunkRef(db()), chunk()));
  await assertFails(setDoc(chunkRef(db(), '1'), chunk(1)));
});

test('chunk updates use getAfter count and excess chunks may be cleaned atomically', async () => {
  const store = db();
  await setDoc(reportRef(store), report({ detailManifest: manifest({ chunkCount: 2 }) }));
  await setDoc(chunkRef(store), chunk());
  await setDoc(chunkRef(store, '1'), chunk(1));
  const invalid = writeBatch(store);
  invalid.update(reportRef(store), { detailManifest: manifest() });
  invalid.set(chunkRef(store, '1'), chunk(1, 'ZGVm'));
  await assertFails(invalid.commit());
  const valid = writeBatch(store);
  valid.update(reportRef(store), { detailManifest: manifest() });
  valid.set(chunkRef(store), chunk(0, 'ZGVm'));
  valid.delete(chunkRef(store, '1'));
  await assertSucceeds(valid.commit());
  assert.equal((await getDocs(chunks(store))).size, 1);
});

test('only explicitly linked doctor can read detail documents and list the patients session chunks', async () => {
  const store = db();
  await setDoc(reportRef(store), report({ detailManifest: manifest() }));
  await setDoc(chunkRef(store), chunk());
  await assertFails(getDoc(chunkRef(db(uid.doctor))));
  await link();
  await assertSucceeds(getDoc(chunkRef(db(uid.doctor))));
  assert.equal((await assertSucceeds(getDocs(chunks(db(uid.doctor))))).size, 1);
  for (const user of [uid.otherDoctor, uid.otherPatient, 'technician']) {
    await assertFails(getDoc(chunkRef(db(user))));
    await assertFails(getDocs(chunks(db(user))));
    await assertFails(setDoc(chunkRef(db(user)), chunk()));
    await assertFails(deleteDoc(chunkRef(db(user))));
  }
  await assertFails(setDoc(chunkRef(db(uid.doctor)), chunk()));
  await assertFails(deleteDoc(chunkRef(db(uid.doctor))));
  await assertFails(setDoc(chunkRef(store, '0', uid.otherPatient), chunk()));
  await deleteDoc(shareRef(store));
  await assertFails(getDoc(chunkRef(db(uid.doctor))));
  await assertFails(getDocs(chunks(db(uid.doctor))));
});

test('deleting parent hides orphan chunks from doctor but owner may clean them up', async () => {
  const store = db();
  await setDoc(reportRef(store), report({ detailManifest: manifest() }));
  await setDoc(chunkRef(store), chunk());
  await link();
  await deleteDoc(reportRef(store));
  await assertFails(getDoc(chunkRef(db(uid.doctor))));
  await assertFails(getDocs(chunks(db(uid.doctor))));
  await assertSucceeds(deleteDoc(chunkRef(store)));
});

test('unauthenticated users cannot read, list, write or delete session details', async () => {
  await setDoc(reportRef(db()), report({ detailManifest: manifest() }));
  await setDoc(chunkRef(db()), chunk());
  const store = env.unauthenticatedContext().firestore();
  await assertFails(getDoc(chunkRef(store)));
  await assertFails(getDocs(chunks(store)));
  await assertFails(setDoc(chunkRef(store), chunk()));
  await assertFails(deleteDoc(chunkRef(store)));
});

test('64 full-size chunks plus manifest can commit in a single bounded batch', async () => {
  const store = db();
  const batch = writeBatch(store);
  batch.set(reportRef(store), report({ detailManifest: manifest({ chunkCount: 64,
    compressedBytes: 6291456, uncompressedBytes: 12582912 }) }));
  const payload = 'A'.repeat(131072);
  for (let index = 0; index < 64; index++) batch.set(chunkRef(store, String(index)), chunk(index, payload));
  await assertSucceeds(batch.commit());
  assert.equal((await getDocs(chunks(store))).size, 64);
});

const malformedManifests = [
  ['null', null], ['wrong type', 'manifest'], ['missing field', { formatVersion: 1, chunkCount: 1 }],
  ['extra field', manifest({ video: true })], ['format version', manifest({ formatVersion: 2 })],
  ['no chunks', manifest({ chunkCount: 0 })], ['too many chunks', manifest({ chunkCount: 65 })],
  ['fractional chunks', manifest({ chunkCount: 1.5 })], ['bad hash', manifest({ sha256: 'BAD' })],
  ['empty compressed', manifest({ compressedBytes: 0 })], ['oversize compressed', manifest({ compressedBytes: 6291457 })],
  ['oversize uncompressed', manifest({ uncompressedBytes: 12582913 })],
];
for (const [label, value] of malformedManifests) test(`rejects malformed detail manifest: ${label}`, async () => {
  await assertFails(setDoc(reportRef(db()), report({ detailManifest: value })));
});

const malformedChunks = [
  ['wrong id', '1', chunk(0)], ['padded id', '00', chunk(0)], ['legacy prefixed id', 'c0000', chunk(0)],
  ['out-of-range id', '64', chunk(64)], ['negative index', '0', chunk(-1)],
  ['fractional index', '0', chunk(0.5)], ['string index', '0', { index: '0', payload: 'YWJj' }],
  ['extra field', '0', { ...chunk(), samples: [] }], ['missing payload', '0', { index: 0 }],
  ['empty payload', '0', chunk(0, '')], ['payload type', '0', chunk(0, 123)],
  ['invalid base64 characters', '0', chunk(0, '!!!!')], ['invalid base64 padding', '0', chunk(0, 'A===')],
  ['incomplete base64', '0', chunk(0, 'AAA')], ['oversize payload', '0', chunk(0, 'A'.repeat(131076))],
];
for (const [label, id, data] of malformedChunks) test(`rejects malformed detail chunk: ${label}`, async () => {
  await setDoc(reportRef(db()), report({ detailManifest: manifest({ chunkCount: 64 }) }));
  await assertFails(setDoc(chunkRef(db(), id), data));
});
