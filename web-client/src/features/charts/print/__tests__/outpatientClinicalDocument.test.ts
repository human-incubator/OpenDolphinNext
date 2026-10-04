import { describe, expect, it } from 'vitest';

import { formatPrintSex } from '../outpatientClinicalDocument';

describe('formatPrintSex', () => {
  it('maps ORCA / local sex codes to 男 / 女', () => {
    expect(formatPrintSex('1')).toBe('男');
    expect(formatPrintSex('M')).toBe('男');
    expect(formatPrintSex('2')).toBe('女');
    expect(formatPrintSex('F')).toBe('女');
    expect(formatPrintSex(undefined)).toBe('-');
    expect(formatPrintSex('女')).toBe('女');
  });
});
