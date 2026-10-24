const test = require('node:test');
const assert = require('node:assert/strict');
const core = require('../app/src/main/assets/food-core.js');

test('macro values accept finite non-negative numbers', () => {
  assert.equal(core.finiteNonNegative(3, 'k'), 3);
  assert.equal(core.finiteNonNegative('4.5', 'k'), 4.5);
  assert.throws(() => core.finiteNonNegative(-1, 'k'));
  assert.throws(() => core.finiteNonNegative(Infinity, 'k'));
});

test('diary validation rejects impossible dates and malformed foods', () => {
  assert.throws(() => core.validateDiary({ diary: { '2026-02-30': [] } }), /Invalid diary/);
  assert.throws(() => core.validateDiary({ diary: { '2026-10-24': [{}] } }), /Invalid food/);
});

test('diary validation returns clean rows with supported fields', () => {
  const macros = Object.fromEntries(core.MACROS.map(key => [key, 1]));
  const result = core.validateDiary({ diary: { '2026-10-24': [{ name: ' Apple ', qty: '1 medium', ...macros, extra: 'discard' }] } });
  const row = result.diary['2026-10-24'][0];
  assert.equal(row.name, 'Apple');
  assert.equal(row.qty, '1 medium');
  assert.equal(row.k, 1);
  assert.equal(row.extra, undefined);
});

test('description parsing recognizes known foods and reports unknown foods', () => {
  const macros = Object.fromEntries(core.MACROS.map(key => [key, 1]));
  const foods = [{ name: 'Apple', unit: 'piece', basis: 'serving', ...macros }];
  const known = core.parseDescription('1 apple', foods);
  assert.equal(known.found.length, 1);
  assert.equal(known.found[0].name, 'Apple');
  const unknown = core.parseDescription('1 dragonfruit', foods);
  assert.equal(unknown.unknown.length, 1);
});
