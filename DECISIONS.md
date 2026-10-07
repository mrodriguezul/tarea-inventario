# Decisiones de diseño

## 1. Alcance y enfoque

La solución se implementó utilizando Java 21 y almacenamiento en memoria, sin incorporar frameworks adicionales ni una base de datos.

El objetivo fue mantener la implementación enfocada en los requisitos funcionales del ejercicio: reservas, expiración, idempotencia, reglas por categoría, alertas de stock y concurrencia.

Dado que el enunciado permite que los datos vivan en memoria en esta etapa, se evitó agregar infraestructura que no fuera necesaria para resolver el problema actual.

## 2. Modelo de inventario

Se diferencia entre el stock físico de un producto y su stock disponible.

Una reserva no descuenta inmediatamente unidades del stock físico. En su lugar, las unidades asociadas a reservas activas se consideran no disponibles.

Por lo tanto:

**Stock disponible = stock físico - unidades en reservas activas**

Cuando una reserva es confirmada, las unidades correspondientes se descuentan definitivamente del stock físico y la reserva deja de estar activa.

Este modelo permite que una reserva expirada libere automáticamente las unidades comprometidas sin tener que restaurar stock físico.

## 3. Expiración de reservas

La duración de una reserva depende de la categoría del producto:

- `STANDARD`: 15 minutos.
- `PRE_ORDER`: 24 horas.
- `FLASH_SALE`: 5 minutos.

Las reglas relacionadas con categorías se encuentran centralizadas en `CategoryPolicy`, evitando distribuir condiciones específicas de cada categoría dentro del flujo principal de reservas.

La expiración se evalúa de manera diferida al ejecutar operaciones sobre el inventario, en lugar de utilizar procesos o hilos en segundo plano.

Además, el servicio recibe un `Clock`, evitando depender directamente de la hora del sistema. Esto permite controlar el tiempo durante las pruebas y verificar la expiración de manera determinística.

## 4. Idempotencia de reservas

El `orderId` se utiliza como clave de idempotencia.

Si se recibe nuevamente una solicitud correspondiente a una reserva que continúa activa, se devuelve la reserva original. El reintento no reserva unidades adicionales ni extiende el tiempo de expiración.

Una vez que una reserva ha expirado y ha sido eliminada, el mismo `orderId` puede utilizarse nuevamente.

Esta decisión permite manejar de forma segura los reintentos que pueden producirse cuando un cliente no recibe la respuesta de una solicitud anterior.

## 5. Reglas por categoría

Las reglas específicas de cada `ProductCategory` se encuentran centralizadas en `CategoryPolicy`.

Actualmente esta política determina la duración de las reservas y, cuando corresponde, el máximo de unidades permitidas por pedido.

Para `FLASH_SALE`, el límite es de dos unidades por pedido.

Mantener estas reglas separadas del flujo principal facilita incorporar o modificar reglas de categorías sin distribuir múltiples condiciones dentro del servicio de inventario.

## 6. Alertas de bajo stock

Se considera bajo stock cuando la cantidad disponible de un producto es menor o igual a cinco unidades.

Una reserva exitosa que deja el producto en este nivel puede generar una notificación mediante `StockAlertListener`.

Para evitar notificaciones repetidas, cada producto mantiene el estado que indica si la alerta de bajo stock ya fue enviada.

Agregar nuevo stock mediante `addStock` rearma esta condición y permite generar una nueva alerta posteriormente. La expiración de una reserva no se considera reposición de stock y, por lo tanto, no rearma la alerta.

## 7. Concurrencia

Las operaciones públicas que acceden o modifican el estado compartido del inventario están sincronizadas.

Esto permite que operaciones como verificar disponibilidad y crear una reserva se ejecuten de forma atómica dentro de una única instancia de la JVM, evitando que solicitudes concurrentes puedan reservar más unidades de las disponibles.

La misma sincronización protege el comportamiento idempotente cuando se reciben concurrentemente varios reintentos asociados al mismo `orderId`.

Se optó por una sincronización simple a nivel del servicio porque el almacenamiento actual es en memoria y el alcance del ejercicio no requiere optimizar la concurrencia por producto.

## 8. Supuestos adoptados

Algunos comportamientos no estaban definidos explícitamente y se resolvieron mediante los siguientes supuestos:

- Registrar nuevamente un SKU con la misma categoría se considera una operación idempotente y conserva el estado existente del producto.
- Intentar registrar un SKU existente con una categoría diferente se rechaza.
- Un `orderId` con una reserva activa representa la misma operación lógica cuando se recibe nuevamente.
- Una reserva expirada deja de considerarse activa y no puede ser confirmada.
- La reposición mediante `addStock` rearma la posibilidad de emitir una alerta de bajo stock.
- La expiración de reservas libera disponibilidad, pero no se considera una reposición de inventario.

## 9. Evolución hacia un entorno de producción

La implementación actual está diseñada para ejecutarse con estado en memoria dentro de una única instancia. La sincronización utilizada no proporciona coordinación entre diferentes instancias del servicio.

En un escenario con múltiples instancias, el inventario y las reservas deberían mantenerse en un almacenamiento compartido y persistente.

Una posible evolución sería utilizar una base de datos transaccional y trasladar el control de concurrencia a la capa de persistencia, utilizando mecanismos como bloqueo de filas o control de concurrencia optimista según las características de carga del sistema.

La idempotencia también debería persistirse, por ejemplo mediante una restricción única asociada al identificador del pedido, de modo que continúe funcionando después de reinicios y entre diferentes instancias del servicio.

Las expiraciones deberían almacenarse de forma persistente y podrían procesarse mediante mecanismos adicionales para liberar o identificar reservas vencidas sin depender exclusivamente del acceso posterior al inventario.

Finalmente, para un sistema distribuido, las notificaciones de bajo stock podrían desacoplarse del flujo principal mediante eventos. Si fuera necesario garantizar consistencia entre la actualización del inventario y la publicación de dichos eventos, podría utilizarse un patrón como Transactional Outbox.

## 10. Criterio general

Se priorizó una solución simple que garantice el comportamiento requerido y que pueda evolucionar posteriormente.

Las decisiones actuales —almacenamiento en memoria, expiración diferida y sincronización a nivel del servicio— responden al alcance actual del ejercicio y no pretenden representar directamente la arquitectura final de un sistema distribuido de producción.