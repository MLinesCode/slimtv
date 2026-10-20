# Cómo se hizo SlimTV con IA

SlimTV salió de **una sola conversación** con [Claude Code](https://claude.com/claude-code), el agente de
programación de Anthropic, usado desde su app de escritorio en Windows. El agente tenía acceso a la terminal
de la computadora y, por medio de ADB, a la TV real en la misma red Wi-Fi. Nada se simuló: cada cambio se
aplicó en la TV, se midió y se verificó con capturas de pantalla.

## Reparto de trabajo

| La persona | La IA |
|---|---|
| Planteó el problema ("mi TV está muy lenta") y compartió una referencia (tv-home) | Descartó la idea de una ROM propia (riesgo de inutilizar la TV) y propuso ADB sin root |
| Activó la depuración USB y aceptó el aviso en la TV | Detectó lo que ya estaba instalado en la PC (SDK, JDK, adb) y se conectó |
| Dio el modelo, la versión de Android y la IP | Hizo el inventario de apps, el respaldo y la medición de RAM por proceso |
| Eligió las apps que usa (Netflix, Crunchyroll, YouTube, Prime Video, HBO Max) | Clasificó ~120 paquetes en "seguro", "opcional" y "no tocar" |
| Aprobó qué desactivar | Aplicó la limpieza y escribió el script para deshacerla |
| — | Diseñó y programó el launcher en Java puro y lo compiló sin Gradle |
| — | Lo instaló, tomó capturas, encontró un defecto visual (el brillo tenía un borde recto) y lo corrigió |
| — | Reinició la TV y descubrió que Netflix, HBO Max y Mercado Play se cargaban solas → las restringió |
| Pidió conservar el trabajo y publicarlo | Escribió la documentación y armó este repositorio |

## La conversación, paso a paso

1. **Planteamiento:** *"Tengo una Android TV muy lenta, ¿podemos limpiar el sistema o hacer una versión propia?"*
   La IA explicó por qué una ROM propia no conviene en una TV y propuso ADB.
2. **Referencia:** la persona compartió tv-home. La IA leyó el repositorio para entender el enfoque
   (debloat + launcher propio) y lo adaptó a una TV con la mitad de RAM.
3. **Conexión:** primero salió `unauthorized`; la persona aceptó el aviso en la TV y ADB quedó conectado.
4. **Diagnóstico:** el dato clave fue **~900 MB de RAM con solo ~265 MB libres**, y que el launcher de Google
   por sí solo usaba 115 MB.
5. **Limpieza:** la IA propuso una lista por grupos; la persona dijo qué apps usa y se aplicó el grupo seguro.
6. **Launcher:** la IA escribió ~600 líneas de Java, sin layouts XML ni librerías, para quedar en 29 KB.
   Revisó cada versión con `adb shell screencap` y "viendo" las capturas, y navegó con `input keyevent`.
7. **Reemplazo:** desactivó el launcher de Google y `setupwraith` (que roba el botón Home), y comprobó
   que el botón "Entradas" siguiera funcionando con el panel de MediaTek.
8. **Descubrimiento:** tras reiniciar, la RAM libre parecía *peor*. La IA investigó y encontró apps de
   streaming arrancando solas (Netflix: 123 MB). Restringirlas fue la mejora más grande.
9. **Resultado:** ~265 MB → ~393 MB de RAM libre, verificado tras un reinicio.
10. **Publicación:** se generalizaron los scripts, se movió la configuración a `config.xml`
    y se escribió esta documentación.

## Por qué funcionó bien con IA

- **El agente veía la TV:** las capturas por ADB le permitieron revisar el diseño y encontrar defectos
  sin que la persona tuviera que describir nada.
- **Todo se midió:** `dumpsys meminfo` antes y después de cada cambio evitó suposiciones. Así se
  descubrió el problema del segundo plano, que no estaba en el plan original.
- **Decisiones humanas en los puntos sensibles:** la IA no desactivó nada sin aprobación y separó
  lo seguro de lo opcional.
- **Todo reversible desde el principio:** el respaldo y el script para deshacer se crearon *antes*
  de modificar nada.

## Repetirlo en tu TV

Usa el prompt de **[PROMPT.md](../PROMPT.md)**. Sigue las mismas fases y deja los mismos puntos de
decisión en tus manos. Cuando termines, comparte la lista de tu modelo en `examples/`
(ver [CONTRIBUTING.md](../CONTRIBUTING.md)).
