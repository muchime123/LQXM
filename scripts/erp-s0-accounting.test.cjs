'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { decimal, format, rejectionMatches } = require('./erp-s0-accounting.cjs');
test('large DECIMAL values retain their smallest unit', () => {
  assert.equal(format(decimal('999999999999999.999999999') - decimal('0.000000001')), '999999999999999.999999998');
});
test('decimal addition is exact', () => assert.equal(format(decimal('0.1') + decimal('0.2')), '0.300000000'));
test('out of range and excess precision fail rather than round', () => {
  for (const value of ['0.0000000001', 'NaN', '1e3', '1000000000000000']) assert.throws(() => decimal(value));
});
test('unexpected exceptions cannot pass rejection assertions', () => {
  assert.equal(rejectionMatches(new Error('SQL failed'), 'ALLOCATION_EXCEEDED'), false);
  assert.equal(rejectionMatches({code:'ER_PARSE_ERROR'}, 'ALLOCATION_EXCEEDED'), false);
  assert.equal(rejectionMatches({code:'ALLOCATION_EXCEEDED'}, 'ALLOCATION_EXCEEDED'), true);
});
