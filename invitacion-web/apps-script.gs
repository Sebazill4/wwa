/**
 * Recibe las confirmaciones de la invitación web y las guarda en la pestaña "Invitados".
 * Si la misma persona confirma de nuevo (mismo nombre), se actualiza su fila en vez de duplicarla.
 *
 * Instalación (una sola vez):
 * 1. Abre la hoja "Invitados Baby Shower Eduardo" → Extensiones → Apps Script.
 * 2. Borra lo que haya, pega este archivo completo y guarda.
 * 3. Implementar → Nueva implementación → tipo "Aplicación web".
 *    Ejecutar como: Yo.  Quién tiene acceso: Cualquier persona.
 * 4. Autoriza con tu cuenta y copia la URL que termina en /exec.
 */
const HOJA = 'Invitados';

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const d = JSON.parse(e.postData.contents || '{}');
    const nombre = String(d.nombre || '').trim().slice(0, 80) || 'Sin nombre';
    const asiste = d.asiste === 'si' ? 'Sí' : 'No';
    const personas = asiste === 'Sí' ? Math.max(1, Math.min(12, Number(d.personas) || 1)) : 0;
    const fila = [new Date(), nombre, asiste, personas];

    const hoja = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(HOJA);
    const ultima = hoja.getLastRow();
    let destino = 0;
    if (ultima >= 2) {
      const nombres = hoja.getRange(2, 2, ultima - 1, 1).getValues();
      const clave = nombre.toLowerCase();
      for (let i = 0; i < nombres.length; i++) {
        if (String(nombres[i][0]).trim().toLowerCase() === clave) { destino = i + 2; break; }
      }
    }
    if (destino) hoja.getRange(destino, 1, 1, fila.length).setValues([fila]);
    else hoja.appendRow(fila);

    return ContentService.createTextOutput('ok');
  } finally {
    lock.releaseLock();
  }
}
