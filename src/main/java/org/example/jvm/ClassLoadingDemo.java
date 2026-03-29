package org.example.jvm;

/**
 * DEMO 1: Class Loading & Initialization Order
 *
 * Bai toan thuc te: Tai sao static config cua ban doi khi bi null
 * du da khai bao? -> Hieu initialization order se tranh bug nay.
 */
public class ClassLoadingDemo {

    public static void main(String[] args) throws Exception {

        // === PHAN 1: Quan sat ClassLoader hierarchy ===
        System.out.println("=== ClassLoader Hierarchy ===");

        ClassLoader appLoader = ClassLoadingDemo.class.getClassLoader();
        System.out.println("App ClassLoader    : " + appLoader);
        System.out.println("Parent (Ext/Platform): " + appLoader.getParent());
        System.out.println("Bootstrap          : " + appLoader.getParent().getParent()); // null = Bootstrap

        // String duoc load boi Bootstrap ClassLoader
        ClassLoader stringLoader = String.class.getClassLoader();
        System.out.println("\nString's ClassLoader: " + stringLoader); // null = Bootstrap

        // === PHAN 2: Static Initialization Order ===
        System.out.println("\n=== Static Initialization Order ===");
        // Chi khi dong nay chay, class Child moi duoc Initialize
        Child child = new Child();
        System.out.println("child.value = " + child.value);

        // === PHAN 3: Class duoc load lazy ===
        System.out.println("\n=== Lazy Class Loading ===");
        System.out.println("LazyClass chua duoc load");
        // Sau dong nay JVM moi load LazyClass
        Class<?> clazz = Class.forName("org.example.jvm.LazyClass");
        System.out.println("LazyClass da duoc load: " + clazz.getName());
    }
}

class Parent {
    static int parentValue;

    static {
        System.out.println("[Parent] Static initializer chay");
        parentValue = 10;
    }

    Parent() {
        System.out.println("[Parent] Constructor chay");
    }
}

class Child extends Parent {
    static int childValue;
    int value;

    static {
        System.out.println("[Child] Static initializer chay");
        // BUG PHIEN: Neu ban dung parentValue o day truoc khi Parent init xong
        // se gap gia tri mac dinh (0), khong phai 10
        childValue = parentValue * 2; // parentValue da san sang vi Parent init truoc
    }

    Child() {
        super(); // Parent constructor chay truoc
        System.out.println("[Child] Constructor chay");
        value = childValue;
    }
}

class LazyClass {
    static {
        System.out.println("[LazyClass] Toi vua duoc load vao JVM!");
    }
}
