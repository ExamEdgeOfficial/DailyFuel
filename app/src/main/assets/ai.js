(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const core = window.DailyFuelFoodCore;
  const CUSTOM_KEY = 'dailyfuel_custom_foods_v2';
  const MODEL_KEY = 'dailyfuel_ai_model';
  let currentPhoto = '';
  let currentRequest = '';
  let pendingItems = [];
  let busy = false;
  const style = document.createElement('style');
  style.textContent = '.ai-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:7px}.ai-grid input{margin-bottom:4px;padding:9px}.ai-item{border:1px solid #e1e8e2;border-radius:12px;padding:11px;margin:9px 0}.ai-photo{display:block;max-width:100%;max-height:220px;border-radius:12px;margin-top:8px}.ai-status{padding:8px 0}.ai-warning{color:#815b13;background:#fff7df;padding:10px;border-radius:10px}.ai-custom-row{padding:8px 0;border-bottom:1px solid #e1e8e2}.ai-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}';
  document.head.appendChild(style);
  const foodCard = $('estimateNote').closest('.card');
  foodCard.insertAdjacentHTML('beforeend', '<div class="ai-actions"><button id="choosePhoto" class="secondary" type="button">Add meal photo</button><button id="analyzePhoto" class="secondary" type="button">Analyze photo</button><button id="removePhoto" class="secondary small hidden" type="button">Remove photo</button></div><input id="mealPhotoInput" class="hidden" type="file" accept="image/*" capture="environment"><img id="mealPhotoPreview" class="ai-photo hidden" alt="Selected meal photo"><div id="aiStatus" class="muted ai-status" role="status"></div>');
  foodCard.insertAdjacentHTML('afterend', '<div id="aiReview" class="card hidden"></div>');
  $('settings').insertAdjacentHTML('beforeend', `<div class="card" id="aiSettings"><h3 style="margin-top:0">AI food estimates</h3><p class="muted">Connect an OpenAI API key to analyze unfamiliar foods and meal photos. AI estimates are approximate and need your review.</p><label for="aiModel">Model</label><input id="aiModel" value="gpt-4o-mini" autocomplete="off"><label for="aiApiKey">OpenAI API key (saved only on this phone)</label><input id="aiApiKey" type="password" autocomplete="off" placeholder="Paste your API key to save it"><div class="ai-actions"><button id="saveAiConfig" type="button">Save AI settings</button><button id="removeAiKey" class="secondary" type="button">Remove saved key</button></div><p id="aiConfigStatus" class="muted" role="status"></p><p class="muted">Each online request sends only the food description and, if selected, the meal photo. Your diary and history stay on this phone and are not sent. OpenAI usage may incur charges and is subject to your account's rate limits. The key is encrypted on-device with Android Keystore; this personal app has no backend, so a compromised or rooted phone can still expose it while in use.</p><p class="muted">Create and manage an API key in your OpenAI Platform account. You can leave this empty and continue using offline foods and custom foods.</p></div><div class="card" id="customFoodsCard"><h3 style="margin-top:0">My custom foods</h3><p class="muted">Save your own nutrition values for offline use. Values can be per serving or per 100 g.</p><form id="customFoodForm"><input id="customId" type="hidden"><label for="customName">Food name</label><input id="customName" maxlength="80" required placeholder="e.g. Homemade soya chaap"><div class="inline"><div><label for="customBasis">Values are per</label><select id="customBasis"><option value="serving">Serving</option><option value="100g">100 g</option></select></div><div><label for="customServing">Serving name</label><input id="customServing" maxlength="40" value="serving" placeholder="bowl, piece, glass"></div></div><div class="ai-grid"><div><label for="customK">Calories (kcal)</label><input id="customK" type="number" min="0" step="any" required></div><div><label for="customP">Protein (g)</label><input id="customP" type="number" min="0" step="any" required></div><div><label for="customC">Carbohydrates (g)</label><input id="customC" type="number" min="0" step="any" required></div><div><label for="customF">Fat (g)</label><input id="customF" type="number" min="0" step="any" required></div><div><label for="customFi">Fibre (g)</label><input id="customFi" type="number" min="0" step="any" required></div></div><div class="ai-actions"><button type="submit">Save custom food</button><button id="cancelCustomEdit" type="button" class="secondary hidden">Cancel edit</button></div></form><div id="customFoodsList" class="muted"></div></div>`);
  $('aiModel').value = localStorage.getItem(MODEL_KEY) || 'gpt-4o-mini';
  function esc(value) { return String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }
  function customFoods() {
    try {
      const list = JSON.parse(localStorage.getItem(CUSTOM_KEY) || '[]');
      return Array.isArray(list) ? list.filter(x => x && typeof x.name === 'string' && ['k','p','c','f','fi'].every(k => Number.isFinite(Number(x[k])) && Number(x[k]) >= 0)) : [];
    } catch (_) { return []; }
  }
  function writeCustomFoods(list) { localStorage.setItem(CUSTOM_KEY, JSON.stringify(list)); renderCustomFoods(); }
  window.parseFoods = text => core.parseDescription(text, DB.concat(customFoods()));
  function keyAvailable() {
    try { return !!(window.AndroidBridge && AndroidBridge.hasAiKey && AndroidBridge.hasAiKey()); }
    catch (_) { return false; }
  }
  function configStatus() {
    $('aiConfigStatus').textContent = keyAvailable() ? 'An encrypted API key is saved on this phone.' : 'No API key saved. Offline foods and manual custom foods still work.';
  }
  function setStatus(text, bad) {
    $('aiStatus').textContent = text || '';
    $('aiStatus').className = 'muted ai-status' + (bad ? ' ai-warning' : '');
  }
  function clearPhoto() {
    currentPhoto = '';
    $('mealPhotoPreview').removeAttribute('src');
    $('mealPhotoPreview').classList.add('hidden');
    $('removePhoto').classList.add('hidden');
    $('mealPhotoInput').value = '';
  }
  function setPhoto(dataUrl) {
    if (!/^data:image\/(jpeg|png|webp);base64,/i.test(dataUrl || '')) { setStatus('That photo could not be read. Choose another image.', true); return; }
    currentPhoto = dataUrl;
    $('mealPhotoPreview').src = dataUrl;
    $('mealPhotoPreview').classList.remove('hidden');
    $('removePhoto').classList.remove('hidden');
    setStatus('Photo ready. Portion and hidden ingredients may be uncertain.', false);
  }
  window.receiveMealPhoto = setPhoto;
  function photoFromFile(file) {
    if (!file || !file.type.startsWith('image/')) { setStatus('Choose an image file.', true); return; }
    const reader = new FileReader();
    reader.onerror = () => setStatus('Could not read the selected photo.', true);
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => setStatus('Could not open that photo.', true);
      img.onload = () => {
        const scale = Math.min(1, 1280 / Math.max(img.width, img.height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(img.width * scale));
        canvas.height = Math.max(1, Math.round(img.height * scale));
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        setPhoto(canvas.toDataURL('image/jpeg', 0.78));
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  }
  $('choosePhoto').onclick = () => {
    if (window.AndroidBridge && AndroidBridge.chooseMealPhoto) AndroidBridge.chooseMealPhoto();
    else $('mealPhotoInput').click();
  };
  $('mealPhotoInput').onchange = e => photoFromFile(e.target.files && e.target.files[0]);
  $('removePhoto').onclick = clearPhoto;
  $('analyzePhoto').onclick = () => {
    if (!currentPhoto) { setStatus('Choose or take a meal photo first.', true); return; }
    const description = $('foodText').value.trim() || 'Identify the foods in this meal photo and estimate the visible serving sizes.';
    requestAi(description, currentPhoto);
  };
  function setBusy(value) {
    busy = value;
    $('estimate').disabled = value;
    $('analyzePhoto').disabled = value;
    if (value) setStatus('Analyzing the food description and photo… Diary history is not sent.', false);
  }
  function requestAi(description, imageData) {
    if (!keyAvailable()) { setStatus('Online AI is unavailable because no API key is saved. Use an offline food or add nutrition manually as a custom food.', true); return; }
    if (busy) return;
    currentRequest = 'r' + Date.now() + Math.random().toString(16).slice(2);
    setBusy(true);
    try { AndroidBridge.analyzeFood(String(description || '').slice(0, 3000), imageData || '', currentRequest, $('aiModel').value); }
    catch (_) { setBusy(false); setStatus('Could not start the AI request. Check the app connection and settings.', true); }
  }
  window.onAIResult = raw => {
    let message;
    try { message = typeof raw === 'string' ? JSON.parse(raw) : raw; }
    catch (_) { setBusy(false); setStatus('The AI service returned malformed data. Nothing was saved.', true); return; }
    if (message.requestId !== currentRequest) return;
    setBusy(false);
    if (!message.ok) { setStatus(message.error || 'AI analysis failed. Your text entry is still available.', true); return; }
    try { showReview(message.result); }
    catch (error) { setStatus('The AI response was incomplete or invalid. Nothing was saved. ' + error.message, true); }
  };
  function showReview(result) {
    if (!result || !Array.isArray(result.items) || result.items.length > 20) throw new Error('Please retry with a shorter food description.');
    const questions = Array.isArray(result.questions) ? result.questions.map(String).slice(0, 6) : [];
    pendingItems = result.items.map(item => core.normalizeNutrition(item));
    if (!pendingItems.length && !questions.length) throw new Error('No food items were identified.');
    const review = $('aiReview');
    const questionHtml = questions.length ? '<div class="ai-warning"><b>Details that may change the estimate:</b><ul>' + questions.map(q => '<li>' + esc(q) + '</li>').join('') + '</ul>Update your description and analyze again for a more tailored estimate.</div>' : '';
    const itemsHtml = pendingItems.map((item, i) => `<div class="ai-item" data-ai-index="${i}"><div class="ai-grid"><div><label>Food</label><input data-field="name" value="${esc(item.name)}" maxlength="120"></div><div><label>Serving</label><input data-field="serving" value="${esc(item.serving)}" maxlength="120"></div><div><label>Calories (kcal)</label><input data-field="k" type="number" min="0" step="any" value="${item.k}"></div><div><label>Protein (g)</label><input data-field="p" type="number" min="0" step="any" value="${item.p}"></div><div><label>Carbohydrates (g)</label><input data-field="c" type="number" min="0" step="any" value="${item.c}"></div><div><label>Fat (g)</label><input data-field="f" type="number" min="0" step="any" value="${item.f}"></div><div><label>Fibre (g)</label><input data-field="fi" type="number" min="0" step="any" value="${item.fi}"></div></div><label class="row" style="align-items:center;gap:8px;margin-top:6px"><input type="checkbox" data-save-custom style="width:auto;margin:0"> Save this food offline as a custom food</label><div class="muted">AI confidence: ${Math.round((item.confidence === undefined ? 0.55 : item.confidence) * 100)}%. ${esc(item.notes)}</div></div>`).join('');
    review.innerHTML = '<h3 style="margin-top:0">Review AI estimate</h3><p class="muted">Check every serving and macro. Photo portion sizes and recipe ingredients can be uncertain. Nothing is saved until you confirm below.</p>' + questionHtml + (itemsHtml || '<p class="muted">No estimate is ready yet. Add the requested details to your description and try again.</p>') + '<div class="ai-actions"><button id="confirmAiItems" type="button" ' + (pendingItems.length ? '' : 'disabled') + '>Add reviewed foods</button><button id="cancelAiItems" class="secondary" type="button">Cancel</button></div>';
    review.classList.remove('hidden');
    $('confirmAiItems').onclick = saveReviewedItems;
    $('cancelAiItems').onclick = () => { review.classList.add('hidden'); review.innerHTML = ''; pendingItems = []; };
    review.scrollIntoView({behavior: 'smooth', block: 'nearest'});
  }
  function saveReviewedItems() {
    const entries = [...$('aiReview').querySelectorAll('.ai-item')].map((row, i) => {
      const values = Object.fromEntries([...row.querySelectorAll('[data-field]')].map(input => [input.dataset.field, input.value]));
      const normalized = core.normalizeNutrition({name: values.name, serving: values.serving, k: values.k, p: values.p, c: values.c, f: values.f, fi: values.fi});
      return {normalized, saveCustom: row.querySelector('[data-save-custom]').checked, confidence: pendingItems[i].confidence};
    });
    if (!entries.length) return;
    entries.forEach(({normalized, saveCustom}) => {
      getItems().push({name: normalized.name, qty: normalized.serving, k: normalized.k, p: normalized.p, c: normalized.c, f: normalized.f, fi: normalized.fi});
      if (saveCustom) {
        const list = customFoods();
        const record = {id: 'ai-' + Date.now() + Math.random().toString(16).slice(2), name: normalized.name, keys: [normalized.name.toLowerCase()], unit: normalized.serving, basis: 'serving', k: normalized.k, p: normalized.p, c: normalized.c, f: normalized.f, fi: normalized.fi};
        const old = list.findIndex(x => x.name.toLowerCase() === record.name.toLowerCase());
        if (old >= 0) list[old] = Object.assign({}, list[old], record, {id: list[old].id}); else list.push(record);
        localStorage.setItem(CUSTOM_KEY, JSON.stringify(list));
      }
    });
    save(); render(); renderCustomFoods();
    $('aiReview').classList.add('hidden'); $('aiReview').innerHTML = ''; pendingItems = [];
    $('foodText').value = ''; clearPhoto();
    setStatus('Added ' + entries.length + ' reviewed estimate(s). Values are approximate.', false);
  }
  $('estimate').onclick = () => {
    const description = $('foodText').value.trim();
    if (!description) { alert('Type what you ate first.'); return; }
    const parsed = window.parseFoods(description);
    if (parsed.unknown.length) {
      if (keyAvailable()) { requestAi(description, currentPhoto); return; }
      if (parsed.found.length) { getItems().push(...parsed.found); save(); render(); }
      setStatus('Offline estimate used for ' + parsed.found.length + ' recognized item(s). Not recognized: ' + parsed.unknown.join('; ') + '. Add a custom food manually or configure AI in Settings. Estimates are approximate.', true);
      return;
    }
    if (!parsed.found.length) { setStatus('No foods were recognized. Add nutrition manually as a custom food or configure online AI.', true); return; }
    getItems().push(...parsed.found); save(); render(); $('foodText').value = ''; setStatus('Added ' + parsed.found.length + ' offline estimate(s). Check portions and edit as needed.', false);
    clearPhoto();
  };
  function resetCustomForm() {
    $('customFoodForm').reset(); $('customId').value = ''; $('customServing').value = 'serving'; $('cancelCustomEdit').classList.add('hidden');
  }
  function renderCustomFoods() {
    const list = customFoods();
    $('customFoodsList').innerHTML = list.length ? list.map(item => `<div class="ai-custom-row"><b>${esc(item.name)}</b><div class="muted">${item.basis === '100g' ? 'Per 100 g' : 'Per ' + esc(item.unit || 'serving')} · ${Math.round(item.k)} kcal · P ${item.p} g · C ${item.c} g · F ${item.f} g · Fibre ${item.fi} g</div><div class="ai-actions"><button class="secondary small" type="button" data-custom-edit="${esc(item.id)}">Edit</button><button class="danger small" type="button" data-custom-delete="${esc(item.id)}">Delete</button></div></div>`).join('') : 'No custom foods saved yet.';
    $('customFoodsList').querySelectorAll('[data-custom-edit]').forEach(button => button.onclick = () => {
      const item = customFoods().find(x => x.id === button.dataset.customEdit); if (!item) return;
      $('customId').value = item.id; $('customName').value = item.name; $('customBasis').value = item.basis === '100g' ? '100g' : 'serving'; $('customServing').value = item.unit || 'serving';
      [['K','k'],['P','p'],['C','c'],['F','f'],['Fi','fi']].forEach(([id,key]) => $('custom' + id).value = item[key]);
      $('cancelCustomEdit').classList.remove('hidden'); $('customName').focus();
    });
    $('customFoodsList').querySelectorAll('[data-custom-delete]').forEach(button => button.onclick = () => {
      if (!confirm('Delete this saved custom food? Existing diary entries will remain.')) return;
      writeCustomFoods(customFoods().filter(x => x.id !== button.dataset.customDelete));
    });
  }
  $('customFoodForm').onsubmit = event => {
    event.preventDefault();
    const name = $('customName').value.trim(), unit = $('customServing').value.trim() || 'serving';
    try {
      const item = {id: $('customId').value || 'custom-' + Date.now(), name, keys: [name.toLowerCase()], unit, basis: $('customBasis').value};
      [['K','k'],['P','p'],['C','c'],['F','f'],['Fi','fi']].forEach(([id,key]) => item[key] = core.finiteNonNegative($('custom' + id).value, key));
      if (!name) throw new Error('Enter a food name.');
      const list = customFoods(); const index = list.findIndex(x => x.id === item.id || x.name.toLowerCase() === name.toLowerCase());
      if (index >= 0) { item.id = list[index].id; list[index] = item; } else list.push(item);
      writeCustomFoods(list); resetCustomForm();
    } catch (error) { alert('Could not save custom food: ' + error.message); }
  };
  $('cancelCustomEdit').onclick = resetCustomForm;
  $('saveAiConfig').onclick = () => {
    const model = $('aiModel').value.trim();
    if (!/^[a-zA-Z0-9._-]{1,100}$/.test(model)) { $('aiConfigStatus').textContent = 'Enter a valid model name.'; return; }
    localStorage.setItem(MODEL_KEY, model);
    const key = $('aiApiKey').value.trim();
    if (!key) { configStatus(); $('aiConfigStatus').textContent = keyAvailable() ? 'Model saved. The existing encrypted key was kept.' : 'Model saved. Add an API key to enable online estimates.'; return; }
    if (!window.AndroidBridge || !AndroidBridge.saveAiKey) { $('aiConfigStatus').textContent = 'Secure API-key storage is available in the Android app. No key was saved in this browser.'; return; }
    $('aiConfigStatus').textContent = 'Encrypting and saving the key on this phone…';
    AndroidBridge.saveAiKey(key); $('aiApiKey').value = '';
  };
  $('removeAiKey').onclick = () => {
    if (!keyAvailable()) { configStatus(); return; }
    if (!confirm('Remove the encrypted API key from this phone?')) return;
    AndroidBridge.saveAiKey('');
  };
  window.onApiKeySaved = (ok, message) => { $('aiConfigStatus').textContent = message || (ok ? 'API key saved encrypted on this phone.' : 'The key could not be saved.'); configStatus(); };
  window.renderCustomFoods = renderCustomFoods; renderCustomFoods(); configStatus();
})();
