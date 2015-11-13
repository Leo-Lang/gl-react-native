package com.projectseptember.RNGL;

import static android.opengl.GLES20.*;

import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.ReadableMapKeySetIterator;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.logging.Logger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class GLCanvas extends GLSurfaceView implements GLSurfaceView.Renderer, RunInGLThread {

    private static final Logger logger = Logger.getLogger(GLCanvas.class.getName());

    private ReactContext reactContext;
    private RNGLContext rnglContext;
    private boolean preloadingDone = false;
    private boolean deferredRendering = false;
    private GLRenderData renderData;
    private int[] defaultFBO;

    private int nbContentTextures;
    private int renderId;
    private boolean opaque;
    private boolean autoRedraw;
    private boolean eventsThrough;
    private boolean visibleContent;
    private int captureNextFrameId;
    private GLData data;
    private ReadableArray imagesToPreload; // TODO we need to make a List<Uri> I guess? probably Uri should be resolved in advance. not in GLImage

    List<String> preloaded; // TODO List<Uri>

    private Map<String, GLImage> images = new HashMap<>();
    private List<GLTexture> contentTextures = new ArrayList<>();

    private Map<Integer, GLShader> shaders = new HashMap<>();
    private Map<Integer, GLFBO> fbos = new HashMap<>();

    public GLCanvas(ThemedReactContext context) {
        super(context);
        reactContext = context;
        this.rnglContext = context.getNativeModule(RNGLContext.class);
        this.setEGLContextClientVersion(2);
        this.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        this.getHolder().setFormat(PixelFormat.RGBA_8888);
        this.setRenderer(this);
        this.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        this.requestRender();

        preloadingDone = true; // TODO
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // FIXME anything to do here?
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // FIXME anything to do here?
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        runAll(mRunOnDraw);

        syncEventsThrough(); // FIXME, really need to do this ?

        if (!preloadingDone) {
            glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            return;
        }

        boolean needsDeferredRendering = nbContentTextures > 0 && !autoRedraw;
        if (needsDeferredRendering && !deferredRendering) {
            deferredRendering = true;
            this.requestRender(); // FIXME is this working correctly?
            /*
            dispatch_async(dispatch_get_main_queue(), ^{
                if (!weakSelf) return;
                deferredRendering = true;
                [weakSelf setNeedsDisplay];
            });
            */
        }
        else {
            this.render();
            deferredRendering = false;
        }
    }

    public void setNbContentTextures(int nbContentTextures) {
        resizeUniformContentTextures(nbContentTextures);
        this.nbContentTextures = nbContentTextures;
    }

    public void setRenderId(int renderId) {
        this.renderId = renderId;
        if (nbContentTextures > 0)
            this.requestRender();
    }

    public void setOpaque(boolean opaque) {
        this.opaque = opaque;
        /* // FIXME: how to do ?
        if (opaque) {
            this.getHolder().setFormat(PixelFormat.RGB_565);
        }
        else {
            this.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }
        */
        this.requestRender(); // FIXME is this required?
    }

    public void setAutoRedraw(boolean autoRedraw) {
        this.autoRedraw = autoRedraw;
        this.setRenderMode(autoRedraw ? GLSurfaceView.RENDERMODE_CONTINUOUSLY : GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }

    public void setEventsThrough(boolean eventsThrough) {
        this.eventsThrough = eventsThrough;
        syncEventsThrough();
    }

    public void setVisibleContent(boolean visibleContent) {
        this.visibleContent = visibleContent;
        syncEventsThrough();
    }

    public void setCaptureNextFrameId(int captureNextFrameId) {
        // FIXME move away from this pattern. just use a method, same to ObjC impl
        this.captureNextFrameId = captureNextFrameId;
        this.requestRender();
    }

    private boolean ensureCompiledShader (List<GLData> data) {
        for (GLData d: data) {
            if (!ensureCompiledShader(d));
                return false;
        }
        return true;
    }

    private boolean ensureCompiledShader (GLData data) {
        GLShader shader = rnglContext.getShader(data.shader);
        if (shader == null) return false;
        shader.ensureCompile();
        return ensureCompiledShader(data.children) && ensureCompiledShader(data.contextChildren);
    }

    public void setData (GLData data) {
        this.data = data;
        this.requestSyncData();
    }


    public void setImagesToPreload(ReadableArray imagesToPreload) {
        logger.info("setImageToPreload, working correctly?"); // TODO
        if (preloadingDone) return;
        if (imagesToPreload.size() == 0) {
            dispatchOnLoad();
            preloadingDone = true;
        }
        else {
            preloadingDone = false;
        }

        this.imagesToPreload = imagesToPreload;
    }

    // Sync methods

    private final Queue<Runnable> mRunOnDraw = new LinkedList<>();
    public void runInGLThread (final Runnable runnable) {
        synchronized (mRunOnDraw) {
            mRunOnDraw.add(runnable);
            requestRender();
        }
    }
    private void runAll(Queue<Runnable> queue) {
        synchronized (queue) {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    public void requestSyncData () {
        runInGLThread(new Runnable() {
            public void run() {
                if (ensureCompiledShader(data))
                    syncData();
                else
                    requestSyncData();
            }
        });
    }

    public void syncContextTextures () {
        int i = 0;
        for (GLTexture texture: contentTextures) {
            View view = ((ViewGroup) this.getParent()).getChildAt(i);
            texture.setPixelsWithView(view);
            i ++;
        }
    }

    public void resizeUniformContentTextures (int n) {
        int length = contentTextures.size();
        if (length == n) return;
        if (n < length) {
            contentTextures = contentTextures.subList(0, n);
        }
        else {
            for (int i = contentTextures.size(); i < n; i++) {
                contentTextures.add(new GLTexture());
            }
        }
    }


    private int countPreloaded () {
        int nb = 0;
        for (int i=0; i<imagesToPreload.size(); i++) {/*
            if ([_preloaded containsObject:srcResource(toload)]) // TODO
            nb++;
            */
        }
        return nb;
    }

    private void onImageLoad (String loaded) {
        if (!preloadingDone) {
            preloaded.add(loaded);
            int count = countPreloaded();
            int total = imagesToPreload.size();
            double progress = ((double) count) / ((double) total);
            dispatchOnProgress(progress, count, total);
            if (count == total) {
                dispatchOnLoad();
                preloadingDone = true;
                requestSyncData();
            }
        }
        else {
            // Any texture image load will trigger a future re-sync of data (if no preloaded)
            requestSyncData();
        }
    }


    public String srcResource (ReadableMap res) {
        String src = null;
        boolean isStatic = res.hasKey("isStatic") && res.getBoolean("isStatic");
        if (res.hasKey("path")) src = res.getString("path");
        if (src==null || isStatic) src = res.getString("uri");
        return src;
    }

    public GLRenderData recSyncData (GLData data, HashMap<String, GLImage> images) {
        Map<String, GLImage> prevImages = this.images;

        GLShader shader = rnglContext.getShader(data.shader);
        Map<String, Integer> uniformsInteger = new HashMap<>();
        Map<String, Float> uniformsFloat = new HashMap<>();
        Map<String, IntBuffer> uniformsIntBuffer = new HashMap<>();
        Map<String, FloatBuffer> uniformsFloatBuffer = new HashMap<>();
        Map<String,GLTexture> textures = new HashMap<>();
        List<GLRenderData> contextChildren = new ArrayList<>();
        List<GLRenderData> children = new ArrayList<>();

        shader.bind();

        for (GLData child: data.contextChildren) {
            contextChildren.add(recSyncData(child, images));
        }

        for (GLData child: data.children) {
            children.add(recSyncData(child, images));
        }

        Map<String, Integer> uniformTypes = shader.getUniformTypes();

        int units = 0;
        ReadableMapKeySetIterator iterator = data.uniforms.keySetIterator();
        while (iterator.hasNextKey()) {
            String uniformName = iterator.nextKey();
            int type = uniformTypes.get(uniformName);

            ReadableMap dataUniforms = data.uniforms;

            if (type == GL_SAMPLER_2D || type == GL_SAMPLER_CUBE) {
                uniformsInteger.put(uniformName, units++);

                if (dataUniforms.isNull(uniformName)) {
                    GLTexture emptyTexture = new GLTexture();
                    emptyTexture.setPixelsEmpty();
                    textures.put(uniformName, emptyTexture);
                }
                else {
                    ReadableMap value = dataUniforms.getMap(uniformName);
                    String t = value.getString("type");
                    if (t.equals("content")) {
                        int id = value.getInt("id");
                        if (id >= contentTextures.size()) {
                            this.resizeUniformContentTextures(id + 1);
                        }
                        textures.put(uniformName, contentTextures.get(id));
                    }
                    else if (t.equals("fbo")) {
                        int id = value.getInt("id");
                        GLFBO fbo = rnglContext.getFBO(id);
                        textures.put(uniformName, fbo.color.get(0));
                    }
                    else if (t.equals("uri")) {
                        final String src = srcResource(value);
                        if (src==null || src.equals("")) {
                            logger.severe("Shader '"+shader.getName()+": texture uniform '"+uniformName+"': Invalid uri format '"+value+"'");
                        }

                        GLImage image = images.get(src);
                        if (image == null) {
                            image = prevImages.get(src);
                            if (image != null)
                                images.put(src, image);
                        }
                        if (image == null) {
                            image = new GLImage(reactContext.getApplicationContext(), this, new Runnable() {
                                public void run() {
                                    onImageLoad(src);
                                }
                            });
                            image.setSrc(src);
                            images.put(src, image);
                        }
                        textures.put(uniformName, image.getTexture());
                    }
                    else {
                        logger.severe("Shader '"+shader.getName()+": texture uniform '"+uniformName+"': Unexpected type '"+type+"'");
                    }
                }
            }
            else {
                switch (type) {
                    case GL_INT:
                        uniformsInteger.put(uniformName, dataUniforms.getInt(uniformName));
                        break;

                    case GL_BOOL:
                        uniformsInteger.put(uniformName, dataUniforms.getBoolean(uniformName) ? 1 : 0);
                        break;

                    case GL_FLOAT:
                        uniformsFloat.put(uniformName, (float) dataUniforms.getDouble(uniformName));
                        break;

                    case GL_FLOAT_VEC2:
                    case GL_FLOAT_VEC3:
                    case GL_FLOAT_VEC4:
                    case GL_FLOAT_MAT2:
                    case GL_FLOAT_MAT3:
                    case GL_FLOAT_MAT4:
                        ReadableArray arr = dataUniforms.getArray(uniformName);
                        if (arraySizeForType(type) != arr.size()) {
                            throw new Error("Shader '"+shader.getName()+
                                    "' uniform '"+uniformName+
                                    "': Invalid array size: "+arr.size()+
                                    ". Expected: "+arraySizeForType(type));
                        }
                        uniformsFloatBuffer.put(uniformName, parseAsFloatArray(arr));
                        break;

                    case GL_INT_VEC2:
                    case GL_INT_VEC3:
                    case GL_INT_VEC4:
                    case GL_BOOL_VEC2:
                    case GL_BOOL_VEC3:
                    case GL_BOOL_VEC4:
                        ReadableArray arr2 = dataUniforms.getArray(uniformName);
                        if (arraySizeForType(type) != arr2.size()) {
                            throw new Error("Shader '"+shader.getName()+
                                    "' uniform '"+uniformName+
                                    "': Invalid array size: "+arr2.size()+
                                    ". Expected: "+arraySizeForType(type));
                        }
                        uniformsIntBuffer.put(uniformName, parseAsIntArray(arr2));
                        break;

                    default:
                        throw new Error("Shader '"+shader.getName()+
                                "' uniform '"+uniformName+
                                "': type not supported: "+type);
                }

            }
        }

        int[] maxTextureUnits = new int[1];
        glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS, maxTextureUnits, 0);
        if (units > maxTextureUnits[0]) {
            logger.severe("Shader '"+shader.getName()+": Maximum number of texture reach. got "+units+" >= max "+maxTextureUnits);
        }

        for (String uniformName: uniformTypes.keySet()) {
            if (!uniformsFloat.containsKey(uniformName) &&
                !uniformsInteger.containsKey(uniformName) &&
                !uniformsFloatBuffer.containsKey(uniformName) &&
                !uniformsIntBuffer.containsKey(uniformName)) {
                logger.severe("Shader '"+shader.getName()+": All defined uniforms must be provided. Missing '"+uniformName+"'");
            }
        }

        return new GLRenderData(
                shader,
                uniformsInteger,
                uniformsFloat,
                uniformsIntBuffer,
                uniformsFloatBuffer,
                textures,
                data.width,
                data.height,
                data.fboId,
                contextChildren,
                children);
    }

    private FloatBuffer parseAsFloatArray(ReadableArray array) {
        int size = array.size();
        FloatBuffer buf = ByteBuffer.allocateDirect(size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (int i=0; i<size; i++)
          buf.put((float) array.getDouble(i));
        buf.position(0);
        return buf;
    }

    private IntBuffer parseAsIntArray(ReadableArray array) {
        int size = array.size();
        IntBuffer buf = ByteBuffer.allocateDirect(size * 4)
                .order(ByteOrder.nativeOrder())
                .asIntBuffer();
        for (int i=0; i<size; i++)
            buf.put(array.getInt(i));
        buf.position(0);
        return buf;
    }

    private int arraySizeForType(int type) {
        switch (type) {
            case GL_FLOAT_VEC2:
            case GL_INT_VEC2:
            case GL_BOOL_VEC2:
                return 2;

            case GL_FLOAT_VEC3:
            case GL_INT_VEC3:
            case GL_BOOL_VEC3:
                return 3;

            case GL_FLOAT_VEC4:
            case GL_INT_VEC4:
            case GL_BOOL_VEC4:
            case GL_FLOAT_MAT2:
                return 4;

            case GL_FLOAT_MAT3:
                return 9;

            case GL_FLOAT_MAT4:
                return 16;

            default:
                throw new Error("Invalid array type: "+type);
        }
    }

    public void syncData () {
        if (data == null) return;
        HashMap<String, GLImage> images = new HashMap<>();
        renderData = recSyncData(data, images);
        this.images = images;
        this.requestRender(); // FIXME don't do it here since syncData is called in render phase
    }

    public void recRender (GLRenderData renderData) {
        DisplayMetrics dm = reactContext.getResources().getDisplayMetrics();

        int w = new Float(renderData.width.floatValue() * dm.density).intValue();
        int h = new Float(renderData.height.floatValue() * dm.density).intValue();

        for (GLRenderData child: renderData.contextChildren)
            recRender(child);

        for (GLRenderData child: renderData.children)
            recRender(child);

        if (renderData.fboId == -1) {
            glBindFramebuffer(GL_FRAMEBUFFER, defaultFBO[0]);
            glViewport(0, 0, w, h);
        }
        else {
            GLFBO fbo = rnglContext.getFBO(renderData.fboId);
            fbo.setShape(w, h);
            fbo.bind();
        }

        renderData.shader.bind();

        for (String uniformName: renderData.textures.keySet()) {
            GLTexture texture = renderData.textures.get(uniformName);
            int unit = renderData.uniformsInteger.get(uniformName);
            texture.bind(unit);
        }

        Map<String, Integer> uniformTypes = renderData.shader.getUniformTypes();
        for (String uniformName: renderData.uniformsInteger.keySet()) {
            renderData.shader.setUniform(uniformName, renderData.uniformsInteger.get(uniformName));
        }
        for (String uniformName: renderData.uniformsFloat.keySet()) {
            renderData.shader.setUniform(uniformName, renderData.uniformsFloat.get(uniformName));
        }
        for (String uniformName: renderData.uniformsFloatBuffer.keySet()) {
            renderData.shader.setUniform(uniformName, renderData.uniformsFloatBuffer.get(uniformName), uniformTypes.get(uniformName));
        }
        for (String uniformName: renderData.uniformsIntBuffer.keySet()) {
            renderData.shader.setUniform(uniformName, renderData.uniformsIntBuffer.get(uniformName), uniformTypes.get(uniformName));
        }

        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        glDrawArrays(GL_TRIANGLES, 0, 6);
    }

    public void render () {
        if (renderData == null) return;
        syncContextTextures();

        defaultFBO = new int[1];
        glGetIntegerv(GL_FRAMEBUFFER_BINDING, defaultFBO, 0);
        glEnable(GL_BLEND);
        recRender(renderData);
        glDisable(GL_BLEND);
        glBindFramebuffer(GL_FRAMEBUFFER, defaultFBO[0]);
    }

    public void syncEventsThrough () {
        // TODO: figure out how to do this (Obj C code below)
        // FIXME: probably we should just define {style} on JS side. check if there is support for touchEvents:"none"
        //self.userInteractionEnabled = !(_eventsThrough);
        //self.superview.userInteractionEnabled = !(_eventsThrough && !_visibleContent);
    }


    private void dispatchOnProgress(double progress, int count, int total) {
        WritableMap event = Arguments.createMap();
        event.putDouble("progress", progress);
        event.putInt("count", count);
        event.putInt("total", total);
        ReactContext reactContext = (ReactContext)getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                "progress",
                event);
    }

    public void dispatchOnLoad () {
        WritableMap event = Arguments.createMap();
        ReactContext reactContext = (ReactContext)getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                "load",
                event);
    }

}