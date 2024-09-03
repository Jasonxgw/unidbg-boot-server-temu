package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.UnidbgProperties;
import com.anjia.unidbgserver.utils.TempFileUtils;
import com.github.unidbg.*;
import com.github.unidbg.Module;
import com.github.unidbg.arm.HookStatus;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.arm.context.Arm32RegisterContext;
import com.github.unidbg.arm.context.RegisterContext;
import com.github.unidbg.debugger.BreakPointCallback;
import com.github.unidbg.file.FileResult;
import com.github.unidbg.file.IOResolver;
import com.github.unidbg.hook.HookContext;
import com.github.unidbg.hook.ReplaceCallback;
import com.github.unidbg.hook.hookzz.*;
import com.github.unidbg.hook.xhook.IxHook;
import com.github.unidbg.linux.android.*;
import com.github.unidbg.linux.android.dvm.*;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.memory.Memory;
import com.github.unidbg.utils.Inspector;
import com.github.unidbg.virtualmodule.android.AndroidModule;
import com.sun.jna.Pointer;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class TemuService extends AbstractJni implements IOResolver {
    private final AndroidEmulator emulator;
    private final VM vm;
    private Module module;
    private static final String ANDROID_ID = "dfaca3bc5d7c82df";
    private final static String UserEnv = "temu/libUserEnv.so";
    private final static String secure = "temu/libsecure_lib.so";
    private Boolean DEBUG_FLAG;

    @SneakyThrows
    TemuService(UnidbgProperties unidbgProperties) {
        DEBUG_FLAG = unidbgProperties.isVerbose();
        // 创建模拟器实例，要模拟32位或者64位，在这里区分
        EmulatorBuilder<AndroidEmulator> builder = AndroidEmulatorBuilder.for32Bit().setProcessName("com.qidian.dldl.official");
        // 动态引擎
        if (unidbgProperties.isDynarmic()) {
            builder.addBackendFactory(new DynarmicFactory(true));
        }
        emulator = AndroidEmulatorBuilder.for64Bit().build(); // 创建模拟器实例，要模拟32位或者64位，在这里区分
        // 模拟器的内存操作接口
        final Memory memory = emulator.getMemory();
        // 设置系统类库解析
        memory.setLibraryResolver(new AndroidResolver(23));
        // 创建Android虚拟机
        vm = emulator.createDalvikVM();
        // 设置代理类（比如so里访问了java的类，具体参看微信的处理）
//        vm.setDvmClassFactory(new ProxyClassFactory());
        // 解决load dependency libandroid.so failed
        new AndroidModule(emulator, vm).register(emulator.getMemory());
        // svc调用日志
        emulator.getSyscallHandler().setVerbose(true);
        emulator.getSyscallHandler().addIOResolver(this);
        // 设置是否打印Jni调用细节
//        vm.setVerbose(true);
        vm.setJni(this);

        SystemPropertyHook systemPropertyHook = new SystemPropertyHook(emulator);
        systemPropertyHook.setPropertyProvider(new SystemPropertyProvider() {
            @Override
            public String getProperty(String key) {
                System.out.println("fuck systemkey:"+key);
                switch (key){
                }
                return "";
            };
        });
        memory.addHookListener(systemPropertyHook);
//        new UserEnvModule(emulator).register(memory);
        // 加载so到虚拟内存，加载成功以后会默认调用init_array等函数
        DalvikModule dm = vm.loadLibrary(TempFileUtils.getTempFile(UserEnv), true);
        dm.callJNI_OnLoad(emulator);

        dm = vm.loadLibrary(TempFileUtils.getTempFile(secure), true);
        // 加载好的 libscmain.so对应为一个模块
        module = dm.getModule();
        // 设置JNI
        vm.setJni(this);

        // HOOK popen
        int popenAddress = (int) module.findSymbolByName("popen").getAddress();
        // 函数原型：FILE *popen(const char *command, const char *type);
        emulator.attach().addBreakPoint(popenAddress, new BreakPointCallback() {
            @Override
            public boolean onHit(Emulator<?> emulator, long address) {
                RegisterContext registerContext = emulator.getContext();
                String command = registerContext.getPointerArg(0).getString(0);
                System.out.println("lilac popen command:"+command);
                emulator.set("command",command);
                return true;
            }
        });
        dm.callJNI_OnLoad(emulator);
    }

    TemuService() {
        emulator = AndroidEmulatorBuilder.for64Bit().build(); // 创建模拟器实例，要模拟32位或者64位，在这里区分
        // 模拟器的内存操作接口
        final Memory memory = emulator.getMemory();
        // 设置系统类库解析
        memory.setLibraryResolver(new AndroidResolver(23));
        // 创建Android虚拟机
        vm = emulator.createDalvikVM();
        // 设置代理类（比如so里访问了java的类，具体参看微信的处理）
//        vm.setDvmClassFactory(new ProxyClassFactory());
        // 解决load dependency libandroid.so failed
        new AndroidModule(emulator, vm).register(emulator.getMemory());
        // svc调用日志
        emulator.getSyscallHandler().setVerbose(true);
        emulator.getSyscallHandler().addIOResolver(this);
        // 设置是否打印Jni调用细节
        vm.setVerbose(true);
        vm.setJni(this);

        SystemPropertyHook systemPropertyHook = new SystemPropertyHook(emulator);
        systemPropertyHook.setPropertyProvider(new SystemPropertyProvider() {
            @Override
            public String getProperty(String key) {
                System.out.println("fuck systemkey:"+key);
                switch (key){
                }
                return "";
            };
        });
        memory.addHookListener(systemPropertyHook);
//        new UserEnvModule(emulator).register(memory);
        // 加载so到虚拟内存，加载成功以后会默认调用init_array等函数
        DalvikModule dm = null;
        try {
            dm = vm.loadLibrary(TempFileUtils.getTempFile(UserEnv), true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        dm.callJNI_OnLoad(emulator);

        try {
            dm = vm.loadLibrary(TempFileUtils.getTempFile(secure), true);
        } catch (IOException e) {
            e.printStackTrace();
        }
        // 加载好的 libscmain.so对应为一个模块
        module = dm.getModule();
        // 设置JNI
        vm.setJni(this);

        // HOOK popen
        int popenAddress = (int) module.findSymbolByName("popen").getAddress();
        // 函数原型：FILE *popen(const char *command, const char *type);
        emulator.attach().addBreakPoint(popenAddress, new BreakPointCallback() {
            @Override
            public boolean onHit(Emulator<?> emulator, long address) {
                RegisterContext registerContext = emulator.getContext();
                String command = registerContext.getPointerArg(0).getString(0);
                System.out.println("lilac popen command:"+command);
                emulator.set("command",command);
                return true;
            }
        });
        dm.callJNI_OnLoad(emulator);
    }

    @Override
    public void callStaticVoidMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        switch (signature){
            case "com/tencent/mars/xlog/PLog->i(Ljava/lang/String;Ljava/lang/String;)V":{
                return;
            }
        }
        super.callStaticVoidMethodV(vm, dvmClass, signature, vaList);
    }
    @Override
    public DvmObject<?> getStaticObjectField(BaseVM vm, DvmClass dvmClass, String signature) {
        switch (signature){
            case "android/provider/Settings$Secure->ANDROID_ID:Ljava/lang/String;":
                return new StringObject(vm, "android_id");
        }
        return super.getStaticObjectField(vm, dvmClass, signature);
    }

    @Override
    public DvmObject callStaticObjectMethod(BaseVM vm, DvmClass dvmClass, String signature, VarArg varArg) {
        // android.provider.Settings$Secure.getString
        if ("android/provider/Settings$Secure->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;".equals(signature)) {
            StringObject key = varArg.getObjectArg(1);

            if ("android_id".equals(key.getValue())) {
                return new StringObject(vm, ANDROID_ID);
            } else {
                System.out.println("android/provider/Settings$Secure->getString key=" + key.getValue());
            }
        }

        return super.callStaticObjectMethod(vm, dvmClass, signature, varArg);
    }
    @Override
    public DvmObject callStaticObjectMethodV(BaseVM vm, DvmClass dvmClass, String signature, VaList vaList) {
        switch (signature){
            case "android/provider/Settings$Secure->getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;":{
                return new StringObject(vm,"android_id");
            }
            case "xmg/mobilebase/secure/EU->gad()Ljava/lang/String;":
                return new StringObject(vm,ANDROID_ID);
        }
        return super.callStaticObjectMethodV(vm, dvmClass, signature, vaList);
    }
    @Override
    public DvmObject callObjectMethodV(BaseVM vm, DvmObject dvmObject, String signature, VaList vaList) {
        switch (signature) {
            case "android/app/ActivityThread->getApplication()Landroid/app/Application;":
                return vm.resolveClass("android/app/Application", vm.resolveClass("android/content/ContextWrapper", vm.resolveClass("android/content/Context"))).newObject(signature);
            case "android/content/Context->getContentResolver()Landroid/content/ContentResolver;":{
                return vm.resolveClass("android/content/ContentResolver").newObject(signature);
            }
            case "java/lang/String->replaceAll(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;":
                StringObject str = (StringObject) dvmObject;
                StringObject s1 = vaList.getObjectArg(0);
                StringObject s2 = vaList.getObjectArg(1);
                return new StringObject(vm, str.getValue().replaceAll(s1.getValue(), s2.getValue()));
        }

        return super.callObjectMethodV(vm, dvmObject, signature, vaList);
    }

    @Override
    public int callIntMethodV(BaseVM vm, DvmObject<?> dvmObject, String signature, VaList vaList) {
        switch (signature){
            case "java/lang/String->hashCode()I":
                String value = dvmObject.getValue().toString();
                return value.hashCode();
        }
        return super.callIntMethodV(vm, dvmObject, signature, vaList);
    }
    public String call_info3(String input){
        List<Object> objectList = new ArrayList<>(10);
        objectList.add(vm.getJNIEnv());
        objectList.add(0);
        objectList.add(vm.addLocalObject(vm.resolveClass("android/content/Context").newObject(null)));
        long l = System.currentTimeMillis();
        System.out.println("--->"+l);
        objectList.add(l);
        objectList.add(vm.addLocalObject(new StringObject(vm,input)));
        Number number = module.callFunction(emulator, 0xd0b8,
                objectList.toArray());
        String  result = (String) vm.getObject(number.intValue()).getValue();
        System.out.println("--->"+result);
        return result;
    }

    public static void main(String[] args) {
        TemuService demo = new TemuService();
        demo.call_info3("123");
    }

    public void destroy() throws IOException {
        emulator.close();
    }
    @Override
    public FileResult resolve(Emulator emulator, String pathname, int oflags) {
        System.out.println("pathName:"+pathname);
        return null;
    }

    @SneakyThrows
    public byte[] ttEncrypt(String input) {
        TemuService demo = new TemuService();
        byte[] bytes = demo.call_info3(input).getBytes(StandardCharsets.UTF_8);
        demo.destroy();
        return bytes;

    }
}
