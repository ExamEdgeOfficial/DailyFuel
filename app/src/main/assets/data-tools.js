(() => {
  'use strict';
  const CUSTOM_KEY = 'dailyfuel_custom_foods_v2';
  function validateCustom(list) {
    if (!Array.isArray(list) || list.length > 1000) throw new Error('Invalid custom-food list');
    return list.map((item, index) => {
      if (!item || typeof item.name !== 'string' || !item.name.trim() || item.name.length > 80) throw new Error('Invalid custom-food name at item ' + (index + 1));
      const name = item.name.trim();
      const clean = {id: String(item.id || 'custom-' + Date.now() + '-' + index).slice(0, 120), name, keys: [name.toLowerCase()], unit: String(item.unit || 'serving').trim().slice(0, 40) || 'serving', basis: item.basis === '100g' ? '100g' : 'serving'};
      ['k','p','c','f','fi'].forEach(k => clean[k] = DailyFuelFoodCore.finiteNonNegative(item[k], k));
      return clean;
    });
  }
  window.backupData = function() {
    const saved = JSON.parse(localStorage.getItem(CUSTOM_KEY) || '[]');
    const customFoods = validateCustom(saved);
    return JSON.stringify({app: 'Daily Fuel Diary', version: 2, exportedAt: new Date().toISOString(), diary, customFoods}, null, 2);
  };
  window.importBackupFromNative = raw => {
    try {
      const backup = typeof raw === 'string' ? JSON.parse(raw) : raw;
      const clean = DailyFuelFoodCore.validateDiary(backup);
      const replacementCustoms = Object.prototype.hasOwnProperty.call(backup, 'customFoods') ? validateCustom(backup.customFoods) : null;
      if (!confirm('Import and replace the current diary? Export a backup first if needed.')) return;
      diary = clean.diary;
      if (replacementCustoms !== null) localStorage.setItem(CUSTOM_KEY, JSON.stringify(replacementCustoms));
      save(); render();
      if (window.renderCustomFoods) window.renderCustomFoods();
      alert('Backup imported.');
    } catch (error) { alert('This backup is invalid or incomplete: ' + error.message); }
  };
  $('export').onclick = () => {
    try {
      const data = backupData();
      if (window.AndroidBridge) AndroidBridge.exportBackup(data);
      else { const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([data], {type: 'application/json'})); a.download = 'daily-fuel-backup.json'; a.click(); URL.revokeObjectURL(a.href); }
    } catch (error) { alert('Could not create backup: ' + error.message); }
  };
  $('clearAll').onclick = () => {
    if (!confirm('Delete all diary entries and saved custom foods from this phone? Export a backup first if needed.')) return;
    diary = {}; save(); localStorage.removeItem(CUSTOM_KEY); render();
    if (window.renderCustomFoods) window.renderCustomFoods();
    alert('Diary and custom foods deleted.');
  };
})();