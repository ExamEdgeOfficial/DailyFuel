(function(root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.DailyFuelFoodCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this, function() {
  const MACROS = ['k', 'p', 'c', 'f', 'fi'];
  const WORDS = {half: 0.5, one: 1, a: 1, an: 1, ek: 1, do: 2, teen: 3, char: 4, chaar: 4, paanch: 5, dedh: 1.5, dhai: 2.5};
  function finiteNonNegative(value, label, max) {
    const n = Number(value);
    if (!Number.isFinite(n) || n < 0 || n > (max || 100000)) throw new Error('Invalid ' + label);
    return n;
  }
  function normalizeNutrition(item) {
    if (!item || typeof item !== 'object') throw new Error('Nutrition item is missing');
    const name = String(item.name || '').trim();
    const serving = String(item.serving || item.qty || '').trim();
    if (!name || name.length > 120 || !serving || serving.length > 120) throw new Error('Food name and serving are required');
    const result = {name, serving};
    const aliases = {k: ['k', 'calories'], p: ['p', 'protein_g'], c: ['c', 'carbs_g'], f: ['f', 'fat_g'], fi: ['fi', 'fibre_g', 'fiber_g']};
    for (const key of MACROS) {
      const source = aliases[key].find(k => item[k] !== undefined);
      if (!source) throw new Error('Missing nutrition value: ' + key);
      result[key] = finiteNonNegative(item[source], key);
    }
    if (item.confidence !== undefined) {
      result.confidence = finiteNonNegative(item.confidence, 'confidence', 1);
    }
    result.notes = String(item.notes || '').slice(0, 300);
    return result;
  }
  function splitDescription(text) {
    return String(text || '').toLowerCase().replace(/\band\b/g, ',').split(/[,;\n]+/).map(x => x.trim()).filter(Boolean);
  }
  function parseDescription(text, foods) {
    const found = [], unknown = [];
    const catalog = (Array.isArray(foods) ? foods : []).flatMap(food => (food.keys && food.keys.length ? food.keys : [food.name]).map(key => ({food, key: String(key).toLowerCase().trim()}))).filter(x => x.key).sort((a, b) => b.key.length - a.key.length);
    for (const chunk of splitDescription(text)) {
      const match = catalog.find(x => chunk.includes(x.key));
      if (!match) { unknown.push(chunk); continue; }
      const food = match.food;
      const qMatch = chunk.match(/(?:^|\s)(\d+(?:\.\d+)?|half|one|a|an|ek|do|teen|char|chaar|paanch|dedh|dhai)\b/i);
      const quantity = qMatch ? (WORDS[qMatch[1].toLowerCase()] ?? Number(qMatch[1])) : 1;
      const unit = String(food.unit || food.serving || 'serving');
      const per100 = food.basis === '100g' || /100\s*g/i.test(unit);
      let multiplier = quantity, qty;
      if (per100) {
        const gramsMatch = chunk.match(/(\d+(?:\.\d+)?)\s*(?:g|grams?)\b/i);
        const grams = gramsMatch ? Number(gramsMatch[1]) : quantity * 100;
        multiplier = grams / 100;
        qty = grams + ' g';
      } else {
        qty = quantity + ' ' + unit;
      }
      const row = {name: food.name, qty};
      for (const key of MACROS) row[key] = finiteNonNegative(food[key], key) * multiplier;
      found.push(row);
    }
    return {found, unknown};
  }
  function isCalendarDate(date) {
  if (typeof date !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return false;
  const parsed = new Date(date + 'T00:00:00Z');
  return !Number.isNaN(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === date;
}

function validateDiary(raw) {
    const backup = typeof raw === 'string' ? JSON.parse(raw) : raw;
    if (!backup || typeof backup !== 'object' || !backup.diary || typeof backup.diary !== 'object' || Array.isArray(backup.diary)) throw new Error('This backup has no diary');
    const diary = {};
    for (const [date, entries] of Object.entries(backup.diary)) {
      if (!isCalendarDate(date) || !Array.isArray(entries)) throw new Error('Invalid diary date or entries');
      diary[date] = entries.map(entry => {
        if (!entry || typeof entry !== 'object' || typeof entry.name !== 'string' || !entry.name.trim() || typeof entry.qty !== 'string') throw new Error('Invalid food entry');
        const clean = {name: entry.name.trim().slice(0, 120), qty: entry.qty.slice(0, 120)};
        for (const key of MACROS) clean[key] = finiteNonNegative(entry[key], key);
        return clean;
      });
    }
    return {diary};
  }
  return {MACROS, finiteNonNegative, normalizeNutrition, splitDescription, parseDescription, validateDiary};
});
