package org.example;

import org.iesalandalus.programacion.utilidades.Entrada;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Aplicación de consola que gestiona departamentos almacenados en el fichero
 * binario de acceso aleatorio {@value #NOMBRE_FICHERO}.
 * Formato del fichero (sin cabecera, separadores ni saltos de línea). Cada
 * posición ocupa {@value #TAMANIO_REGISTRO} bytes con estos campos, en este orden:
 * Número de departamento (short): 2 bytes
 * Nombre (15 caracteres UTF-16): 30 bytes
 * Localidad (15 caracteres UTF-16): 30 bytes
 * Número de empleados (short): 2 bytes
 * El departamento se guarda siempre en la posición, es decir,
 * en el desplazamiento (n - 1) x 64
 */
public class GestionDepartamentos {

    /** Nombre del fichero (ruta relativa al directorio de ejecución). */
    private static final String NOMBRE_FICHERO = "AleatorioDepart.dat";

    /** Bytes que ocupa cada posición completa del fichero. */
    private static final int TAMANIO_REGISTRO = 64;

    /** Número de caracteres (unidades UTF-16) de los campos de texto. */
    private static final int LONGITUD_TEXTO = 15;

    /** Desplazamiento del nombre dentro del registro; la localidad sigue al nombre (en el byte 32). */
    private static final int OFFSET_NOMBRE = 2;

    /** Desplazamiento del número de empleados dentro del registro (2 + 30 + 30 bytes). */
    private static final int OFFSET_EMPLEADOS = 62;

    /** Menor número de departamento válido. */
    private static final int NUMERO_MINIMO = 1;

    /** Mayor número de departamento válido (máximo valor de un short). */
    private static final int NUMERO_MAXIMO = 32767;

    /** Número de departamento que marca una posición nunca utilizada. */
    private static final short NUMERO_NUNCA_UTILIZADA = 0;

    /** Número de departamento (y de empleados) que marca un registro eliminado. */
    private static final short MARCA_NUMERICA_ELIMINADA = -1;

    /** Texto de nombre y localidad en una posición nunca utilizada. */
    private static final String MARCA_NUNCA_UTILIZADA = "-".repeat(LONGITUD_TEXTO);

    /** Texto de nombre y localidad en un registro eliminado. */
    private static final String MARCA_ELIMINADA = "*".repeat(LONGITUD_TEXTO);

    /** Indica si la aplicación está en modo diagnóstico (se detectaron anomalías). */
    private static boolean modoDiagnostico = false;

    /**
     * Estados en los que puede encontrarse la posición de un departamento.
     */
    private enum EstadoPosicion {
        FUERA_DEL_FICHERO("posición situada fuera del fichero"),
        NUNCA_UTILIZADA("posición nunca utilizada"),
        ELIMINADA("registro eliminado"),
        ACTIVA("registro activo"),
        ANOMALA("registro anómalo");

        private final String descripcion;

        EstadoPosicion(String descripcion) {
            this.descripcion = descripcion;
        }
    }

    /**
     * Contenido de una posición completa del fichero, tal como está almacenado.
     */
    private static class Registro {
        private final short numeroDepartamento;
        private final String nombre;
        private final String localidad;
        private final short numeroEmpleados;

        private Registro(short numeroDepartamento, String nombre, String localidad, short numeroEmpleados) {
            this.numeroDepartamento = numeroDepartamento;
            this.nombre = nombre;
            this.localidad = localidad;
            this.numeroEmpleados = numeroEmpleados;
        }
    }

    // ------------------------------------------------------------------
    // Programa principal y menú
    // ------------------------------------------------------------------

    /**
     * Punto de entrada: prepara el fichero y muestra el menú hasta que el usuario sale.
     *
     * @param args argumentos de línea de comandos (no se utilizan)
     */
    public static void main(String[] args) {
        System.out.println("Iniciando la aplicación de gestión de departamentos...");
        if (prepararFichero()) {
            ejecutarMenu();
        }
    }

    /**
     * Crea el fichero vacío si no existe. Si existe conserva su contenido y comprueba
     * su integridad; si hay anomalías, activa el modo diagnóstico e informa de ellas.
     *
     * @return {@code true} si la aplicación puede continuar; {@code false} si no es posible
     */
    private static boolean prepararFichero() {
        File fichero = new File(NOMBRE_FICHERO);
        try {
            if (fichero.isDirectory()) {
                System.out.println("Error: '" + fichero.getAbsolutePath() + "' es un directorio, no un fichero.");
                return false;
            }
            if (!fichero.exists()) {
                fichero.createNewFile();
                System.out.println("El fichero no existía y se ha creado vacío en: " + fichero.getAbsolutePath());
                System.out.println();
                return true;
            }
            if (!fichero.canRead() || !fichero.canWrite()) {
                System.out.println("Error: no hay permisos de lectura y escritura sobre " + fichero.getAbsolutePath());
                return false;
            }

            System.out.println("El fichero ya existe (" + fichero.length() + " bytes). Comprobando su integridad...");
            List<String> problemas = comprobarIntegridad();
            if (problemas.isEmpty()) {
                System.out.println("Integridad del fichero verificada correctamente.");
            } else {
                modoDiagnostico = true;
                System.out.println();
                System.out.println("¡ADVERTENCIA! Se han detectado anomalías de integridad en el fichero:");
                for (String problema : problemas) {
                    System.out.println("  - " + problema);
                }
                System.out.println("La aplicación entra en MODO DE DIAGNÓSTICO: solo se permiten la inspección física,");
                System.out.println("las estadísticas y la comprobación de integridad. El fichero no se repara.");
            }
            System.out.println();
            return true;
        } catch (IOException e) {
            System.out.println("Error de acceso al preparar el fichero: " + e.getMessage());
            return false;
        }
    }

    /**
     * Muestra el menú y ejecuta las opciones elegidas hasta que se selecciona salir.
     * Aquí se gestionan, de forma diferenciada, los errores de cada operación.
     */
    private static void ejecutarMenu() {
        int opcion;
        do {
            mostrarMenu();
            opcion = leerEntero("Seleccione una opción: ", 0, 10);
            System.out.println();

            if (modoDiagnostico && !opcionPermitidaEnDiagnostico(opcion)) {
                System.out.println("-> Operación denegada: en modo diagnóstico solo se permiten las opciones 8, 9 y 10.");
                System.out.println();
                continue;
            }

            try {
                ejecutarOpcion(opcion);
            } catch (IllegalArgumentException e) {
                System.out.println("Validación incorrecta: " + e.getMessage());
            } catch (FileNotFoundException e) {
                System.out.println("Error de acceso: no se encuentra o no se puede abrir el fichero (" + e.getMessage() + ").");
            } catch (EOFException e) {
                System.out.println("Error de acceso: se alcanzó el final del fichero antes de lo esperado (registro incompleto).");
            } catch (IOException e) {
                System.out.println("Error de acceso al fichero: " + e.getMessage());
            }
            System.out.println();
        } while (opcion != 0);
    }

    /**
     * Imprime las opciones del menú.
     */
    private static void mostrarMenu() {
        System.out.println("=== MENÚ DE DEPARTAMENTOS ===");
        if (modoDiagnostico) {
            System.out.println("[MODO DIAGNÓSTICO ACTIVO]");
        }
        System.out.println(" 1. Comprobar si existe un departamento");
        System.out.println(" 2. Consultar un departamento");
        System.out.println(" 3. Insertar un departamento");
        System.out.println(" 4. Actualizar el número de empleados");
        System.out.println(" 5. Actualizar nombre y localidad");
        System.out.println(" 6. Eliminar un departamento");
        System.out.println(" 7. Listado de departamentos activos");
        System.out.println(" 8. Inspección física del fichero");
        System.out.println(" 9. Estadísticas");
        System.out.println("10. Informe de integridad");
        System.out.println(" 0. Salir");
    }

    /**
     * Indica si una opción puede ejecutarse en modo diagnóstico.
     *
     * @param opcion opción del menú
     * @return {@code true} para salir, inspección, estadísticas e integridad
     */
    private static boolean opcionPermitidaEnDiagnostico(int opcion) {
        return opcion == 0 || opcion == 8 || opcion == 9 || opcion == 10;
    }

    /**
     * Ejecuta la opción del menú indicada, leyendo antes los datos que necesite.
     *
     * @param opcion opción elegida (0 a 10)
     * @throws IOException si falla el acceso al fichero
     */
    private static void ejecutarOpcion(int opcion) throws IOException {
        switch (opcion) {
            case 1:
                existeDepartamento(leerNumeroDepartamento("Número de departamento a comprobar"));
                break;
            case 2:
                consultaDepartamento(leerNumeroDepartamento("Número de departamento a consultar"));
                break;
            case 3:
                opcionInsertar();
                break;
            case 4:
                int numeroActualizar = leerNumeroDepartamento("Número de departamento");
                int nuevosEmpleados = leerNumeroEmpleados("Nuevo número de empleados");
                actualizaNumeroEmpleados(numeroActualizar, nuevosEmpleados);
                break;
            case 5:
                opcionActualizarNombreLocalidad();
                break;
            case 6:
                opcionEliminar();
                break;
            case 7:
                listadoDepartamentos();
                break;
            case 8:
                inspeccionFisica();
                break;
            case 9:
                estadisticas();
                break;
            case 10:
                integridad();
                break;
            case 0:
                System.out.println("Finalizando la aplicación...");
                break;
            default:
                break;
        }
    }

    /**
     * Pide los datos de un departamento nuevo y lo inserta.
     *
     * @throws IOException si falla el acceso al fichero
     */
    private static void opcionInsertar() throws IOException {
        int numero = leerNumeroDepartamento("Número de departamento");
        String nombre = solicitarTexto("Nombre (máximo " + LONGITUD_TEXTO + " caracteres): ", "nombre");
        String localidad = solicitarTexto("Localidad (máximo " + LONGITUD_TEXTO + " caracteres): ", "localidad");
        int empleados = leerNumeroEmpleados("Número de empleados");
        insertaDepartamento(numero, nombre, localidad, empleados);
    }

    /**
     * Pide un número de departamento, un nombre y una localidad, y los actualiza.
     *
     * @throws IOException si falla el acceso al fichero
     */
    private static void opcionActualizarNombreLocalidad() throws IOException {
        int numero = leerNumeroDepartamento("Número de departamento");
        String nombre = solicitarTexto("Nuevo nombre (máximo " + LONGITUD_TEXTO + " caracteres): ", "nombre");
        String localidad = solicitarTexto("Nueva localidad (máximo " + LONGITUD_TEXTO + " caracteres): ", "localidad");
        actualizaNombreLocalidad(numero, nombre, localidad);
    }

    /**
     * Pide el departamento a eliminar y, si está activo, solicita confirmación
     * antes de realizar el borrado lógico.
     *
     * @throws IOException si falla el acceso al fichero
     */
    private static void opcionEliminar() throws IOException {
        int numero = leerNumeroDepartamento("Número de departamento a eliminar");
        if (estadoDepartamento(numero) != EstadoPosicion.ACTIVA) {
            // No hay nada que confirmar: el propio método informa de que no existe.
            eliminaDepartamento(numero);
            return;
        }
        System.out.print("¿Está seguro de que desea eliminar el departamento " + numero + "? (S/N): ");
        if (Entrada.cadena().trim().equalsIgnoreCase("S")) {
            eliminaDepartamento(numero);
        } else {
            System.out.println("Eliminación cancelada. El fichero no se ha modificado.");
        }
    }

    // ------------------------------------------------------------------
    // Operaciones sobre los departamentos
    // ------------------------------------------------------------------

    /**
     * Comprueba si existe un departamento (registro activo) y muestra en qué situación
     * se encuentra su posición: fuera del fichero, nunca utilizada, eliminada, activa o anómala.
     *
     * @param numeroDepartamento número de departamento (1 a 32767)
     * @return {@code true} si existe un registro activo con ese número; {@code false} en otro caso
     * @throws IllegalArgumentException si el número no es válido (validación incorrecta)
     * @throws IOException              si falla la lectura (error de acceso, no significa "no existe")
     */
    public static boolean existeDepartamento(int numeroDepartamento) throws IOException {
        EstadoPosicion estado = estadoDepartamento(numeroDepartamento);
        System.out.println("Departamento " + numeroDepartamento + ": " + estado.descripcion + ".");
        return estado == EstadoPosicion.ACTIVA;
    }

    /**
     * Muestra todos los datos de un departamento mediante acceso directo a su posición.
     *
     * @param numeroDepartamento número de departamento (1 a 32767)
     * @throws IllegalArgumentException si el número no es válido
     * @throws IOException              si falla la lectura
     */
    public static void consultaDepartamento(int numeroDepartamento) throws IOException {
        validarNumeroDepartamento(numeroDepartamento);
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "r")) {
            EstadoPosicion estado = obtenerEstado(fichero, numeroDepartamento);
            if (estado != EstadoPosicion.ACTIVA) {
                informarNoActivo(numeroDepartamento, estado, false);
                return;
            }
            Registro registro = leerRegistro(fichero, numeroDepartamento);
            System.out.println("--- DEPARTAMENTO " + registro.numeroDepartamento + " ---");
            System.out.println("Nombre:    " + registro.nombre.stripTrailing());
            System.out.println("Localidad: " + registro.localidad.stripTrailing());
            System.out.println("Empleados: " + registro.numeroEmpleados);
        }
    }

    /**
     * Actualiza únicamente el número de empleados de un departamento existente.
     * Se coloca el puntero directamente en ese campo y no se reescribe el resto del registro.
     *
     * @param numeroDepartamento     número de departamento (1 a 32767)
     * @param nuevoNumeroEmpleados   nuevo número de empleados (0 a 32767)
     * @throws IllegalArgumentException si algún valor no es válido
     * @throws IOException              si falla el acceso al fichero
     */
    public static void actualizaNumeroEmpleados(int numeroDepartamento, int nuevoNumeroEmpleados)
            throws IOException {
        validarNumeroDepartamento(numeroDepartamento);
        validarNumeroEmpleados(nuevoNumeroEmpleados);

        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "rw")) {
            EstadoPosicion estado = obtenerEstado(fichero, numeroDepartamento);
            if (estado != EstadoPosicion.ACTIVA) {
                informarNoActivo(numeroDepartamento, estado, true);
                return;
            }
            fichero.seek(desplazamiento(numeroDepartamento) + OFFSET_EMPLEADOS);
            fichero.writeShort(nuevoNumeroEmpleados);
            System.out.println("Número de empleados del departamento " + numeroDepartamento
                    + " actualizado a " + nuevoNumeroEmpleados + ".");
        }
    }

    /**
     * Actualiza el nombre y la localidad de un departamento existente. Ambos textos se
     * validan antes de la primera escritura: si alguno es inválido no se modifica nada.
     *
     * @param numeroDepartamento número de departamento (1 a 32767)
     * @param nombre             nuevo nombre (hasta 15 caracteres)
     * @param localidad          nueva localidad (hasta 15 caracteres)
     * @throws IllegalArgumentException si algún valor no es válido
     * @throws IOException              si falla el acceso al fichero
     */
    public static void actualizaNombreLocalidad(int numeroDepartamento, String nombre, String localidad)
            throws IOException {
        validarNumeroDepartamento(numeroDepartamento);
        // Validación previa de los dos textos: aún no se ha escrito nada en el fichero.
        String nombreNormalizado = normalizarTexto(nombre, "nombre");
        String localidadNormalizada = normalizarTexto(localidad, "localidad");

        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "rw")) {
            EstadoPosicion estado = obtenerEstado(fichero, numeroDepartamento);
            if (estado != EstadoPosicion.ACTIVA) {
                informarNoActivo(numeroDepartamento, estado, true);
                return;
            }
            // Nombre y localidad son campos contiguos: basta un único posicionamiento.
            fichero.seek(desplazamiento(numeroDepartamento) + OFFSET_NOMBRE);
            fichero.writeChars(nombreNormalizado);
            fichero.writeChars(localidadNormalizada);
            System.out.println("Nombre y localidad del departamento " + numeroDepartamento + " actualizados.");
        }
    }

    /**
     * Inserta un departamento. Solo es posible si su posición está nunca utilizada, eliminada
     * o más allá del final del fichero (en ese caso se inicializan antes los huecos intermedios
     * como posiciones nunca utilizadas). Si ya existe, o la posición es anómala, no se modifica nada.
     *
     * @param numeroDepartamento número de departamento (1 a 32767)
     * @param nombre             nombre (hasta 15 caracteres)
     * @param localidad          localidad (hasta 15 caracteres)
     * @param numeroEmpleados    número de empleados (0 a 32767)
     * @throws IllegalArgumentException si algún valor no es válido
     * @throws IOException              si falla el acceso al fichero
     */
    public static void insertaDepartamento(int numeroDepartamento, String nombre, String localidad,
                                           int numeroEmpleados) throws IOException {
        validarNumeroDepartamento(numeroDepartamento);
        String nombreNormalizado = normalizarTexto(nombre, "nombre");
        String localidadNormalizada = normalizarTexto(localidad, "localidad");
        validarNumeroEmpleados(numeroEmpleados);

        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "rw")) {
            long longitud = fichero.length();
            if (longitud % TAMANIO_REGISTRO != 0) {
                throw new IOException("la longitud del fichero (" + longitud + " bytes) no es múltiplo de "
                        + TAMANIO_REGISTRO + "; no se puede insertar sin dañar el formato");
            }

            EstadoPosicion estado = obtenerEstado(fichero, numeroDepartamento);
            if (estado == EstadoPosicion.ACTIVA) {
                System.out.println("El departamento " + numeroDepartamento
                        + " ya existe. El fichero no se ha modificado.");
                return;
            }
            if (estado == EstadoPosicion.ANOMALA) {
                informarNoActivo(numeroDepartamento, estado, true);
                return;
            }

            if (estado == EstadoPosicion.FUERA_DEL_FICHERO) {
                inicializarHuecos(fichero, longitud, desplazamiento(numeroDepartamento));
            }
            fichero.seek(desplazamiento(numeroDepartamento));
            fichero.write(construirRegistro((short) numeroDepartamento, nombreNormalizado,
                    localidadNormalizada, (short) numeroEmpleados));
            System.out.println("Departamento " + numeroDepartamento + " insertado correctamente.");
        }
    }

    /**
     * Elimina lógicamente un departamento existente: escribe -1 en el número, quince asteriscos
     * en nombre y localidad y -1 en el número de empleados. La longitud del fichero no cambia.
     * La confirmación previa la solicita el menú.
     *
     * @param numeroDepartamento número de departamento (1 a 32767)
     * @throws IllegalArgumentException si el número no es válido
     * @throws IOException              si falla el acceso al fichero
     */
    public static void eliminaDepartamento(int numeroDepartamento) throws IOException {
        validarNumeroDepartamento(numeroDepartamento);
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "rw")) {
            EstadoPosicion estado = obtenerEstado(fichero, numeroDepartamento);
            if (estado != EstadoPosicion.ACTIVA) {
                informarNoActivo(numeroDepartamento, estado, true);
                return;
            }
            fichero.seek(desplazamiento(numeroDepartamento));
            fichero.write(construirRegistro(MARCA_NUMERICA_ELIMINADA, MARCA_ELIMINADA,
                    MARCA_ELIMINADA, MARCA_NUMERICA_ELIMINADA));
            System.out.println("Departamento " + numeroDepartamento + " eliminado.");
        }
    }

    /**
     * Muestra los departamentos activos ordenados por número (recorrido secuencial del fichero)
     * y, al final, cuántos se han mostrado.
     *
     * @throws IOException si falla la lectura
     */
    public static void listadoDepartamentos() throws IOException {
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "r")) {
            int posiciones = (int) (fichero.length() / TAMANIO_REGISTRO);
            int mostrados = 0;
            System.out.println("--- LISTADO DE DEPARTAMENTOS ACTIVOS ---");
            for (int numero = 1; numero <= posiciones; numero++) {
                Registro registro = leerRegistroActual(fichero);
                if (clasificar(numero, registro) == EstadoPosicion.ACTIVA) {
                    System.out.printf("Número: %5d | Nombre: %-15s | Localidad: %-15s | Empleados: %5d%n",
                            registro.numeroDepartamento, registro.nombre.stripTrailing(),
                            registro.localidad.stripTrailing(), registro.numeroEmpleados);
                    mostrados++;
                }
            }
            if (mostrados == 0) {
                System.out.println("No hay ningún departamento activo en el fichero.");
            } else {
                System.out.println("Departamentos mostrados: " + mostrados);
            }
        }
    }

    /**
     * Muestra todas las posiciones completas del fichero (también las nunca utilizadas,
     * las eliminadas y las anómalas) con su desplazamiento, identificadores y estado.
     *
     * @throws IOException si falla la lectura
     */
    public static void inspeccionFisica() throws IOException {
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "r")) {
            long longitud = fichero.length();
            int posiciones = (int) (longitud / TAMANIO_REGISTRO);
            System.out.println("--- INSPECCIÓN FÍSICA DEL FICHERO ---");
            if (posiciones == 0) {
                System.out.println("El fichero no contiene ninguna posición completa.");
            }
            for (int numero = 1; numero <= posiciones; numero++) {
                Registro registro = leerRegistroActual(fichero);
                EstadoPosicion estado = clasificar(numero, registro);
                System.out.printf("Posición: %d | Desplazamiento: %d bytes | ID esperado: %d | "
                                + "ID almacenado: %d | Estado: %s%n",
                        numero, desplazamiento(numero), numero, registro.numeroDepartamento,
                        primeraEnMayuscula(estado.descripcion));
            }
            long sobrantes = longitud % TAMANIO_REGISTRO;
            if (sobrantes != 0) {
                System.out.println("Atención: desde el byte " + (longitud - sobrantes) + " hay " + sobrantes
                        + " bytes que no forman una posición completa.");
            }
        }
    }

    /**
     * Muestra estadísticas del fichero: longitud, activos, eliminados, nunca utilizados,
     * suma de empleados de los activos y porcentaje de ocupación.
     *
     * @throws IOException si falla la lectura
     */
    public static void estadisticas() throws IOException {
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "r")) {
            long longitud = fichero.length();
            int posiciones = (int) (longitud / TAMANIO_REGISTRO);
            int activas = 0;
            int eliminadas = 0;
            int nuncaUtilizadas = 0;
            int anomalas = 0;
            long totalEmpleados = 0;

            for (int numero = 1; numero <= posiciones; numero++) {
                Registro registro = leerRegistroActual(fichero);
                switch (clasificar(numero, registro)) {
                    case ACTIVA:
                        activas++;
                        totalEmpleados += registro.numeroEmpleados;
                        break;
                    case ELIMINADA:
                        eliminadas++;
                        break;
                    case NUNCA_UTILIZADA:
                        nuncaUtilizadas++;
                        break;
                    default:
                        anomalas++;
                        break;
                }
            }

            int posicionesClasificadas = activas + eliminadas + nuncaUtilizadas;
            double ocupacion = posicionesClasificadas > 0 ? 100.0 * activas / posicionesClasificadas : 0;

            System.out.println("--- ESTADÍSTICAS ---");
            System.out.println("Longitud del fichero (bytes):       " + longitud);
            System.out.println("Departamentos activos:              " + activas);
            System.out.println("Posiciones eliminadas:              " + eliminadas);
            System.out.println("Posiciones nunca utilizadas:        " + nuncaUtilizadas);
            System.out.println("Suma de empleados (activos):        " + totalEmpleados);
            System.out.printf("Porcentaje de ocupación:            %.2f %% (activas sobre el total de posiciones)%n",
                    ocupacion);
            if (anomalas > 0) {
                System.out.println("ATENCIÓN: hay " + anomalas + " posiciones anómalas (no se cuentan arriba).");
            }
            if (longitud % TAMANIO_REGISTRO != 0) {
                System.out.println("ATENCIÓN: la longitud no es múltiplo de " + TAMANIO_REGISTRO + " bytes.");
            }
        }
    }

    /**
     * Ejecuta y muestra el informe de integridad. Es una operación de diagnóstico:
     * no repara ni sobrescribe ningún dato.
     *
     * @throws IOException si falla la lectura
     */
    public static void integridad() throws IOException {
        List<String> problemas = comprobarIntegridad();
        System.out.println("--- INFORME DE INTEGRIDAD ---");
        if (problemas.isEmpty()) {
            System.out.println("El fichero ha superado todas las comprobaciones sin problemas.");
            return;
        }
        for (String problema : problemas) {
            System.out.println("  - " + problema);
        }
        System.out.println("Problemas detectados: " + problemas.size() + ". No se ha modificado el fichero.");
    }

    /**
     * Recorre el fichero y recopila los problemas de integridad, indicando su posición en bytes.
     * Detecta, como mínimo: longitud no múltiplo de 64, identificadores negativos distintos de -1,
     * identificadores positivos que no corresponden a su posición, empleados negativos o textos
     * vacíos en registros activos, posiciones nunca utilizadas con formato incorrecto y registros
     * eliminados sin las marcas de borrado.
     *
     * @return lista de problemas (vacía si el fichero es íntegro)
     * @throws IOException si falla la lectura
     */
    public static List<String> comprobarIntegridad() throws IOException {
        List<String> problemas = new ArrayList<>();
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "r")) {
            long longitud = fichero.length();
            int posiciones = (int) (longitud / TAMANIO_REGISTRO);

            if (longitud % TAMANIO_REGISTRO != 0) {
                problemas.add("Byte " + (long) posiciones * TAMANIO_REGISTRO + ": la longitud del fichero ("
                        + longitud + " bytes) no es múltiplo de " + TAMANIO_REGISTRO
                        + "; el último registro está incompleto.");
            }
            for (int numero = 1; numero <= posiciones; numero++) {
                problemas.addAll(describirAnomalias(numero, leerRegistroActual(fichero)));
            }
        }
        return problemas;
    }

    // ------------------------------------------------------------------
    // Lectura, escritura y clasificación de registros
    // ------------------------------------------------------------------

    /**
     * Devuelve el estado de la posición de un departamento sin mostrar nada por pantalla.
     *
     * @param numeroDepartamento número de departamento (1 a 32767)
     * @return estado de la posición
     * @throws IllegalArgumentException si el número no es válido
     * @throws IOException              si falla la lectura
     */
    private static EstadoPosicion estadoDepartamento(int numeroDepartamento) throws IOException {
        validarNumeroDepartamento(numeroDepartamento);
        try (RandomAccessFile fichero = new RandomAccessFile(NOMBRE_FICHERO, "r")) {
            return obtenerEstado(fichero, numeroDepartamento);
        }
    }

    /**
     * Obtiene el estado de una posición accediendo directamente a ella.
     *
     * @param fichero            fichero abierto
     * @param numeroDepartamento número de departamento ya validado
     * @return estado de la posición
     * @throws IOException si falla la lectura
     */
    private static EstadoPosicion obtenerEstado(RandomAccessFile fichero, int numeroDepartamento)
            throws IOException {
        long inicio = desplazamiento(numeroDepartamento);
        long longitud = fichero.length();
        if (inicio >= longitud) {
            return EstadoPosicion.FUERA_DEL_FICHERO;
        }
        if (inicio + TAMANIO_REGISTRO > longitud) {
            return EstadoPosicion.ANOMALA; // registro incompleto al final del fichero
        }
        return clasificar(numeroDepartamento, leerRegistro(fichero, numeroDepartamento));
    }

    /**
     * Calcula el desplazamiento inicial (en bytes) de la posición de un departamento.
     *
     * @param numeroDepartamento número de departamento
     * @return (numeroDepartamento - 1) x 64
     */
    private static long desplazamiento(int numeroDepartamento) {
        return (long) (numeroDepartamento - 1) * TAMANIO_REGISTRO;
    }

    /**
     * Lee el registro de una posición concreta (acceso directo con seek).
     *
     * @param fichero            fichero abierto
     * @param numeroDepartamento número de departamento
     * @return registro leído
     * @throws IOException si falla la lectura
     */
    private static Registro leerRegistro(RandomAccessFile fichero, int numeroDepartamento) throws IOException {
        fichero.seek(desplazamiento(numeroDepartamento));
        return leerRegistroActual(fichero);
    }

    /**
     * Lee el registro que empieza en la posición actual del puntero (acceso secuencial).
     *
     * @param fichero fichero abierto
     * @return registro leído
     * @throws IOException si falla la lectura (EOFException si el registro está incompleto)
     */
    private static Registro leerRegistroActual(RandomAccessFile fichero) throws IOException {
        byte[] bytes = new byte[TAMANIO_REGISTRO];
        fichero.readFully(bytes);
        DataInputStream entrada = new DataInputStream(new ByteArrayInputStream(bytes));
        short numeroDepartamento = entrada.readShort();
        String nombre = leerTexto(entrada);
        String localidad = leerTexto(entrada);
        short numeroEmpleados = entrada.readShort();
        return new Registro(numeroDepartamento, nombre, localidad, numeroEmpleados);
    }

    /**
     * Lee un campo de texto de {@value #LONGITUD_TEXTO} caracteres.
     *
     * @param entrada flujo del que se leen los caracteres
     * @return texto leído, con su relleno
     * @throws IOException si falla la lectura
     */
    private static String leerTexto(DataInputStream entrada) throws IOException {
        char[] caracteres = new char[LONGITUD_TEXTO];
        for (int i = 0; i < LONGITUD_TEXTO; i++) {
            caracteres[i] = entrada.readChar();
        }
        return new String(caracteres);
    }

    /**
     * Construye los 64 bytes de un registro para escribirlo en una sola operación.
     *
     * @param numeroDepartamento número de departamento (o marca)
     * @param nombre             nombre ya con 15 caracteres
     * @param localidad          localidad ya con 15 caracteres
     * @param numeroEmpleados    número de empleados (o marca)
     * @return array de 64 bytes
     * @throws IOException si falla la escritura en el flujo en memoria
     */
    private static byte[] construirRegistro(short numeroDepartamento, String nombre, String localidad,
                                            short numeroEmpleados) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(TAMANIO_REGISTRO);
        DataOutputStream salida = new DataOutputStream(bytes);
        salida.writeShort(numeroDepartamento);
        salida.writeChars(nombre);
        salida.writeChars(localidad);
        salida.writeShort(numeroEmpleados);
        return bytes.toByteArray();
    }

    /**
     * Escribe posiciones "nunca utilizadas" desde el final actual del fichero hasta
     * el inicio de la posición que se va a insertar.
     *
     * @param fichero       fichero abierto en modo lectura y escritura
     * @param longitud      longitud actual del fichero (múltiplo de 64)
     * @param inicioDestino desplazamiento de la posición que se va a escribir
     * @throws IOException si falla la escritura
     */
    private static void inicializarHuecos(RandomAccessFile fichero, long longitud, long inicioDestino)
            throws IOException {
        byte[] posicionVacia = construirRegistro(NUMERO_NUNCA_UTILIZADA, MARCA_NUNCA_UTILIZADA,
                MARCA_NUNCA_UTILIZADA, NUMERO_NUNCA_UTILIZADA);
        fichero.seek(longitud);
        for (long inicio = longitud; inicio < inicioDestino; inicio += TAMANIO_REGISTRO) {
            fichero.write(posicionVacia);
        }
    }

    /**
     * Clasifica una posición según su contenido. Una posición es anómala si incumple
     * alguna de las reglas de los demás estados.
     *
     * @param numeroEsperado número de departamento que corresponde a la posición
     * @param registro       contenido almacenado
     * @return estado de la posición (nunca {@code FUERA_DEL_FICHERO})
     */
    private static EstadoPosicion clasificar(int numeroEsperado, Registro registro) {
        if (!describirAnomalias(numeroEsperado, registro).isEmpty()) {
            return EstadoPosicion.ANOMALA;
        }
        if (registro.numeroDepartamento == NUMERO_NUNCA_UTILIZADA) {
            return EstadoPosicion.NUNCA_UTILIZADA;
        }
        if (registro.numeroDepartamento == MARCA_NUMERICA_ELIMINADA) {
            return EstadoPosicion.ELIMINADA;
        }
        return EstadoPosicion.ACTIVA;
    }

    /**
     * Describe los problemas de integridad de una posición (lista vacía si es correcta).
     *
     * @param numeroEsperado número de departamento que corresponde a la posición
     * @param registro       contenido almacenado
     * @return mensajes de anomalía, cada uno con el byte en que empieza la posición
     */
    private static List<String> describirAnomalias(int numeroEsperado, Registro registro) {
        List<String> problemas = new ArrayList<>();
        String lugar = "Byte " + desplazamiento(numeroEsperado) + " (posición " + numeroEsperado + "): ";
        short numero = registro.numeroDepartamento;

        if (numero < MARCA_NUMERICA_ELIMINADA) {
            problemas.add(lugar + "identificador negativo distinto de -1 (" + numero + ").");
        } else if (numero > 0 && numero != numeroEsperado) {
            problemas.add(lugar + "el identificador positivo " + numero
                    + " no corresponde a su posición (se esperaba " + numeroEsperado + ").");
        } else if (numero == numeroEsperado) {
            if (registro.numeroEmpleados < 0) {
                problemas.add(lugar + "registro activo con número de empleados negativo ("
                        + registro.numeroEmpleados + ").");
            }
            if (registro.nombre.isBlank()) {
                problemas.add(lugar + "registro activo con el nombre vacío.");
            } else if (registro.nombre.charAt(0) == ' ') {
                problemas.add(lugar + "registro activo con el nombre mal formado (espacios iniciales).");
            }
            if (registro.localidad.isBlank()) {
                problemas.add(lugar + "registro activo con la localidad vacía.");
            } else if (registro.localidad.charAt(0) == ' ') {
                problemas.add(lugar + "registro activo con la localidad mal formada (espacios iniciales).");
            }
        } else if (numero == NUMERO_NUNCA_UTILIZADA) {
            if (!registro.nombre.equals(MARCA_NUNCA_UTILIZADA) || !registro.localidad.equals(MARCA_NUNCA_UTILIZADA)
                    || registro.numeroEmpleados != 0) {
                problemas.add(lugar + "posición nunca utilizada que no respeta su formato "
                        + "(0, quince guiones, quince guiones, 0).");
            }
        } else {
            if (!registro.nombre.equals(MARCA_ELIMINADA) || !registro.localidad.equals(MARCA_ELIMINADA)
                    || registro.numeroEmpleados != MARCA_NUMERICA_ELIMINADA) {
                problemas.add(lugar + "registro eliminado sin las marcas de borrado "
                        + "(-1, quince asteriscos, quince asteriscos, -1).");
            }
        }
        return problemas;
    }

    /**
     * Informa de por qué no se puede operar sobre un departamento que no está activo.
     *
     * @param numeroDepartamento número de departamento
     * @param estado             estado de su posición
     * @param modificacion       {@code true} si la operación iba a modificar el fichero
     */
    private static void informarNoActivo(int numeroDepartamento, EstadoPosicion estado, boolean modificacion) {
        String aviso = modificacion ? " El fichero no se ha modificado." : "";
        if (estado == EstadoPosicion.ANOMALA) {
            System.out.println("La posición del departamento " + numeroDepartamento
                    + " contiene un registro anómalo; revise el informe de integridad." + aviso);
        } else {
            System.out.println("El departamento " + numeroDepartamento + " no existe ("
                    + estado.descripcion + ")." + aviso);
        }
    }

    /**
     * Pone en mayúscula la primera letra de un texto.
     *
     * @param texto texto no vacío
     * @return el mismo texto con la primera letra en mayúscula
     */
    private static String primeraEnMayuscula(String texto) {
        return Character.toUpperCase(texto.charAt(0)) + texto.substring(1);
    }

    // ------------------------------------------------------------------
    // Validaciones
    // ------------------------------------------------------------------

    /**
     * Comprueba que un número de departamento esté entre 1 y 32767.
     *
     * @param numeroDepartamento número a comprobar
     * @throws IllegalArgumentException si está fuera de rango
     */
    private static void validarNumeroDepartamento(int numeroDepartamento) {
        if (numeroDepartamento < NUMERO_MINIMO || numeroDepartamento > NUMERO_MAXIMO) {
            throw new IllegalArgumentException("el número de departamento debe estar entre "
                    + NUMERO_MINIMO + " y " + NUMERO_MAXIMO + " (recibido: " + numeroDepartamento + ")");
        }
    }

    /**
     * Comprueba que el número de empleados esté entre 0 y 32767.
     *
     * @param numeroEmpleados número a comprobar
     * @throws IllegalArgumentException si está fuera de rango
     */
    private static void validarNumeroEmpleados(int numeroEmpleados) {
        if (numeroEmpleados < 0 || numeroEmpleados > NUMERO_MAXIMO) {
            throw new IllegalArgumentException("el número de empleados debe estar entre 0 y "
                    + NUMERO_MAXIMO + " (recibido: " + numeroEmpleados + ")");
        }
    }

    /**
     * Valida y prepara un texto para guardarlo: elimina los espacios iniciales y finales,
     * conserva los interiores, rechaza los textos vacíos o de más de 15 caracteres
     * (sin truncar nunca) y completa con espacios a la derecha hasta 15 caracteres.
     *
     * @param texto texto introducido
     * @param campo nombre del campo, para los mensajes de error
     * @return texto de exactamente 15 caracteres
     * @throws IllegalArgumentException si el texto es nulo, vacío o demasiado largo
     */
    private static String normalizarTexto(String texto, String campo) {
        String limpio = texto == null ? "" : texto.strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException("el campo " + campo + " no puede estar vacío "
                    + "ni formado solo por espacios");
        }
        if (limpio.length() > LONGITUD_TEXTO) {
            throw new IllegalArgumentException("el campo " + campo + " tiene " + limpio.length()
                    + " caracteres y el máximo es " + LONGITUD_TEXTO + "; no se admite el truncamiento");
        }
        return String.format("%-" + LONGITUD_TEXTO + "s", limpio);
    }

    // ------------------------------------------------------------------
    // Lectura de datos por teclado (clase Entrada)
    // ------------------------------------------------------------------

    /**
     * Pide un texto hasta que sea válido (no vacío y de hasta 15 caracteres).
     *
     * @param mensaje mensaje que se muestra al usuario
     * @param campo   nombre del campo, para los mensajes de error
     * @return texto introducido por el usuario (la normalización se aplica al guardarlo)
     */
    private static String solicitarTexto(String mensaje, String campo) {
        while (true) {
            System.out.print(mensaje);
            String texto = Entrada.cadena();
            try {
                normalizarTexto(texto, campo);
                return texto;
            } catch (IllegalArgumentException e) {
                System.out.println(" -> Error: " + e.getMessage() + ".");
            }
        }
    }

    /**
     * Pide un número de departamento válido (1 a 32767).
     *
     * @param descripcion texto que se muestra antes del rango
     * @return número de departamento
     */
    private static int leerNumeroDepartamento(String descripcion) {
        return leerEntero(descripcion + " (" + NUMERO_MINIMO + "-" + NUMERO_MAXIMO + "): ",
                NUMERO_MINIMO, NUMERO_MAXIMO);
    }

    /**
     * Pide un número de empleados válido (0 a 32767).
     *
     * @param descripcion texto que se muestra antes del rango
     * @return número de empleados
     */
    private static int leerNumeroEmpleados(String descripcion) {
        return leerEntero(descripcion + " (0-" + NUMERO_MAXIMO + "): ", 0, NUMERO_MAXIMO);
    }

    /**
     * Pide un número entero dentro de un rango, repitiendo la petición hasta que sea correcto.
     *
     * @param mensaje mensaje que se muestra al usuario
     * @param minimo  valor mínimo admitido
     * @param maximo  valor máximo admitido
     * @return entero leído dentro del rango
     */
    private static int leerEntero(String mensaje, int minimo, int maximo) {
        while (true) {
            System.out.print(mensaje);
            try {
                int valor = Integer.parseInt(Entrada.cadena().trim());
                if (valor >= minimo && valor <= maximo) {
                    return valor;
                }
                System.out.println(" -> Error: el valor debe estar entre " + minimo + " y " + maximo + ".");
            } catch (NumberFormatException e) {
                System.out.println(" -> Error: debe introducir un número entero.");
            }
        }
    }
}
