/**
 * Recibe las confirmaciones de la invitación web y las guarda en la pestaña "Invitados"
 * (columnas: Fecha de respuesta, Nombre, Apellido, ¿Asiste?, Personas, Notas).
 * Si la misma persona confirma de nuevo (mismo nombre y apellido), se actualiza su fila.
 * Si el invitado escribe un mensaje en la botella, se guarda en la columna Notas.
 */
const HOJA = 'Invitados';

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    const d = JSON.parse((e && e.postData && e.postData.contents) || '{}');
    const limpiar = s => String(s || '').trim().replace(/\s+/g, ' ').slice(0, 60);
    const nombre = limpiar(d.nombre), apellido = limpiar(d.apellido);
    if (!nombre || !apellido) return ContentService.createTextOutput('falta nombre');
    const asiste = d.asiste === 'si' ? 'Sí' : 'No';
    const personas = asiste === 'Sí' ? Math.max(1, Math.min(12, Number(d.personas) || 1)) : 0;
    const fila = [new Date(), nombre, apellido, asiste, personas];

    const hoja = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(HOJA);
    const ultima = hoja.getLastRow();
    const clave = (nombre + ' ' + apellido).toLowerCase();
    let destino = 0;
    if (ultima >= 2) {
      const datos = hoja.getRange(2, 2, ultima - 1, 2).getValues();
      for (let i = 0; i < datos.length; i++) {
        const k = (String(datos[i][0]).trim() + ' ' + String(datos[i][1]).trim()).toLowerCase();
        if (k === clave) { destino = i + 2; break; }
      }
    }
    if (!destino) destino = ultima + 1;
    hoja.getRange(destino, 1, 1, fila.length).setValues([fila]);
    const nota = String(d.nota || '').trim().slice(0, 300);
    if (nota) hoja.getRange(destino, 6).setValue(nota);
    return ContentService.createTextOutput('ok');
  } finally {
    lock.releaseLock();
  }
}
