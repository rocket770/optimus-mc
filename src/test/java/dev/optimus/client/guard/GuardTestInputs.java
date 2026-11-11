package dev.optimus.client.guard;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntPredicate;

/** Installs a deterministic device without exporting native input implementation details. */
public final class GuardTestInputs {
    private GuardTestInputs() {}

    public static Object owner(Object root, String name) throws Exception {
        Object found = owner(root, name, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (found == null) throw new NoSuchFieldException(name);
        return found;
    }

    private static Object owner(Object object, String name, Set<Object> seen) throws Exception {
        if (object == null || !seen.add(object)) return null;
        try {
            object.getClass().getDeclaredField(name);
            return object;
        } catch (NoSuchFieldException ignored) {
        }
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !field.getType().getName().startsWith("dev.optimus.client.guard.")) continue;
            field.setAccessible(true);
            Object found = owner(field.get(object), name, seen);
            if (found != null) return found;
        }
        return null;
    }

    public static void install(GuardController guard, BooleanSupplier focused, IntPredicate pressed)
            throws Exception {
        Object input = owner(guard, "desktopKeys");
        Field field = input.getClass().getDeclaredField("desktopKeys");
        field.setAccessible(true);
        GuardWindowsKeys reader =
                new GuardWindowsKeys(
                        new GuardWindowsKeys.Device() {
                            public long windowId() {
                                return 1;
                            }

                            public int processId() {
                                return 1;
                            }

                            public boolean gameplayFocused() {
                                return focused.getAsBoolean();
                            }

                            public boolean down(int key) {
                                return pressed.test(key);
                            }
                        });
        field.set(input, reader);
    }
}