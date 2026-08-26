package com.tyranor.next.scanner;

import android.os.Environment;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEnvironment;

/**
 * 内置 ShadowEnvironment 未覆盖 R+ 新增的 isExternalStorageManager（直调会落到真实框架代码）。
 * 继承内置 shadow 并补齐该方法，注入授权状态供门禁回归用例确定性切换「已授权/未授权」分支。
 */
@Implements(Environment.class)
public class TestableShadowEnvironment extends ShadowEnvironment {

    private static volatile boolean externalStorageManager = false;

    public static void setExternalStorageManager(boolean value) {
        externalStorageManager = value;
    }

    @Implementation
    public static boolean isExternalStorageManager() {
        return externalStorageManager;
    }
}
