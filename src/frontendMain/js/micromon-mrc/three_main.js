import * as THREE                     from 'three'
import   WEBGL                        from 'three/examples/jsm/capabilities/WebGL'
import { OrbitControls }              from 'three/examples/jsm/controls/OrbitControls'
import { VolumeRenderShader1 }        from 'three/examples/jsm/shaders/VolumeShader'
import { GUI, GUIController }         from 'dat.gui' /* So that we can use JSDoc with out a warning about importing GUIController --> */ // eslint-disable-line
import { readMrc }                    from './mrc'
import { hexToRgb }                   from './three_help'
import * as DEFAULT                   from './three_defaults'
import {fragmentShader, vertexShader, fragmentShader2} from './three_shaders'

let globalRenderer1, globalRenderer2, globalHostDiv1, globalHostDiv2, globalScene1, globalScene2;
let updateUniformsFunction;
let updateSecondCamera = () => { }
let currentCamera1, currentCamera2;

let render1Needed = true;
let render2Needed = true;

let currentInstanceID = 0 // Used to keep track of when new instances have been created

/**
 * @type {THREE.PerspectiveCamera|undefined}
 */
let globalCamera1 = undefined; 

/**
 * @type {THREE.PerspectiveCamera|undefined}
 */
let globalCamera2 = undefined; 

let firstMeshShowing  = false;
let secondMeshShowing = false;
let usingDoubleViews  = true;


let /** @type {THREE.Mesh} */ isoMesh1, 
    /** @type {THREE.Mesh} */ mipMesh1, 
    /** @type {THREE.Mesh} */ isoMesh2, 
    /** @type {THREE.Mesh} */ mipMesh2,
    /** @type {THREE.Mesh} */ combinedIsoMesh;

let isoTexture1, isoTexture2;

export function checkFirstMeshIsShowing()  { return firstMeshShowing }
export function checkSecondMeshIsShowing() { return secondMeshShowing }
export function checkUsingDoubleViews() { return usingDoubleViews }

function resetVolconfig(obj) {
    obj.clim1 =        DEFAULT.CLIM[0]
    obj.clim2 =        DEFAULT.CLIM[1]
    obj.renderstyle =  DEFAULT.RENDERSTYLE
    obj.isothreshold = DEFAULT.ISOTHRESHOLD
    obj.colormap =     DEFAULT.COLORMAP
    obj.sharpness =    DEFAULT.SHARPNESS
    obj.firstColor =   DEFAULT.FIRST_COLOR
    obj.secondColor =  DEFAULT.SECOND_COLOR
    obj.steps =        DEFAULT.STEPS
    updateUniformsFunction()
    updateRenderer()
};

const volconfig = {
    clim1:        DEFAULT.CLIM[0], 
    clim2:        DEFAULT.CLIM[1], 
    renderstyle:  DEFAULT.RENDERSTYLE, 
    isothreshold: DEFAULT.ISOTHRESHOLD, 
    colormap:     DEFAULT.COLORMAP, 
    sharpness:    DEFAULT.SHARPNESS,
    firstColor:   DEFAULT.FIRST_COLOR,
    secondColor:  DEFAULT.SECOND_COLOR,
    steps:        DEFAULT.STEPS,
    reset:        () => resetVolconfig(volconfig)
};

const cmtextures = {
    viridis: new THREE.TextureLoader().load( 'https://threejs.org/examples/textures/cm_viridis.png' ),
    gray: new THREE.TextureLoader().load( 'https://threejs.org/examples/textures/cm_gray.png' )
};

function makeIsoTextureFromFloatData(rImage, lX, lY, lZ) {
    const imageData = new Uint8Array(rImage.map(v => Math.round(256 * v))) // Scale values up from (0, 1) to (0, 256)

    // Texture to hold the volume. We have scalars, so we put our data in the red channel.
    const isoTexture = new THREE.Data3DTexture(imageData, lX, lY, lZ);
    isoTexture.format = THREE.RedFormat;
    isoTexture.minFilter = isoTexture.magFilter = THREE.LinearFilter;
    isoTexture.unpackAlignment = 1;
    isoTexture.needsUpdate = true;

    return isoTexture
}

function createIsoMesh(isoTexture) {
    
    const isoGeometry = new THREE.BoxGeometry( 1, 1, 1 );
    const isoMaterial = new THREE.RawShaderMaterial( {
        glslVersion: THREE.GLSL3,
        uniforms: {
            map: { value: isoTexture },
            cameraPos: { value: new THREE.Vector3() },
            threshold: { value: DEFAULT.ISOTHRESHOLD },
            steps: { value: DEFAULT.STEPS },
            redValue: { value: DEFAULT.FIRST_COLOR_VALUES.r },
            greenValue: { value: DEFAULT.FIRST_COLOR_VALUES.g },
            blueValue: { value: DEFAULT.FIRST_COLOR_VALUES.b }
        },
        vertexShader,
        fragmentShader,
        side: THREE.BackSide,
    } );

    return new THREE.Mesh( isoGeometry, isoMaterial );
}

function createEmptyDoubleIsoMesh() {
    
    const isoGeometry = new THREE.BoxGeometry( 1, 1, 1 );
    const isoMaterial = new THREE.RawShaderMaterial( {
        glslVersion: THREE.GLSL3,
        uniforms: {
            cameraPos: { value: new THREE.Vector3() },
            threshold: { value: DEFAULT.ISOTHRESHOLD },
            steps: { value: DEFAULT.STEPS },
            map1: { value: null },
            map2: { value: null },
            redValue1: { value: DEFAULT.FIRST_COLOR_VALUES.r },
            greenValue1: { value: DEFAULT.FIRST_COLOR_VALUES.g },
            blueValue1: { value: DEFAULT.FIRST_COLOR_VALUES.b },
            redValue2: { value: DEFAULT.SECOND_COLOR_VALUES.r },
            greenValue2: { value: DEFAULT.SECOND_COLOR_VALUES.g },
            blueValue2: { value: DEFAULT.SECOND_COLOR_VALUES.b }
        },
        vertexShader,
        fragmentShader: fragmentShader2,
        side: THREE.BackSide,
    } );

    return new THREE.Mesh( isoGeometry, isoMaterial );
}

function createMipMesh(rImage, lX, lY, lZ) {
    const shader = VolumeRenderShader1;
    const uniforms = THREE.UniformsUtils.clone( shader.uniforms );

    const mipTexture = new THREE.Data3DTexture(rImage, lX, lY, lZ);
    mipTexture.format = THREE.RedFormat;
    mipTexture.type = THREE.FloatType;
    mipTexture.minFilter = mipTexture.magFilter = THREE.LinearFilter;
    mipTexture.unpackAlignment = 1;
    mipTexture.needsUpdate = true;

    uniforms[ "u_data" ].value = mipTexture;
    uniforms[ "u_size" ].value.set( lX, lY, lZ );
    uniforms[ "u_clim" ].value.set( volconfig.clim1, volconfig.clim2 );
    uniforms[ "u_renderstyle" ].value = 0; // 0: MIP, 1: ISO
    uniforms[ "u_renderthreshold" ].value = volconfig.isothreshold; // For ISO renderstyle
    uniforms[ "u_cmdata" ].value = cmtextures[ volconfig.colormap ];

    const mipMaterial = new THREE.ShaderMaterial( {
        uniforms: uniforms,
        vertexShader: shader.vertexShader,
        fragmentShader: shader.fragmentShader,
        side: THREE.BackSide
    } );

    const mipGeometry = new THREE.BoxGeometry( lX, lY, lZ );
    mipGeometry.translate( lX / 2 - 0.5, lY / 2 - 0.5, lZ / 2 - 0.5 );

    const mipMesh = new THREE.Mesh( mipGeometry, mipMaterial );
    mipMesh.scale.x *= 1 / lX
    mipMesh.scale.y *= 1 / lY
    mipMesh.scale.z *= 1 / lZ
    mipMesh.translateOnAxis( new THREE.Vector3(-1, -1, -1), 0.5)

    return mipMesh;
}

export function clearMesh1() {

    globalScene1.remove(isoMesh1)
    globalScene1.remove(mipMesh1)
    if (usingDoubleViews || !secondMeshShowing) {
        globalScene1.remove(combinedIsoMesh)
    }

    if (usingDoubleViews) globalHostDiv1.style.display = "none"

    combinedIsoMesh.material.uniforms.map1.value = null

    firstMeshShowing = false
    updateUniformsFunction()
    render1Needed = true
}

export function createMesh1fromData(data) {

    if (isoMesh1) isoMesh1.material.dispose() // Fix issues with lingering Uint8Array instances 
    if (mipMesh1) mipMesh1.material.dispose() 
    if (isoTexture1) isoTexture1.dispose()

    if (firstMeshShowing) clearMesh1()
    let imageData = processArrayBufferResponse(data)
    isoTexture1 = makeIsoTextureFromFloatData(...imageData)
    isoMesh1 = createIsoMesh(isoTexture1)
    mipMesh1 = createMipMesh(...imageData)
    imageData = null

    refreshMesh1Data()
    render1Needed = true
}

function refreshMesh1Data() {
    
    if (usingDoubleViews) {
        globalScene1.add( isoMesh1 );
        globalScene1.add( mipMesh1 );
    } else {
        // Note, it is possible that this code will be reached even
        // when combinedIsoMesh is already part of the scene. However, 
        // this is not an issue, and the object is not added twice. 
        // (verified by examining Object3D.add in Three.JS source code)
        globalScene1.add( combinedIsoMesh )
    }

    globalHostDiv1.style.display = "inline-block"

    combinedIsoMesh.material.uniforms.map1.value = isoTexture1

    firstMeshShowing = true
    updateUniformsFunction()
    updateRenderer()
}

export function clearMesh2() {
    
    globalHostDiv2.style.display = "none"

    globalScene2.remove(isoMesh2)
    globalScene2.remove(mipMesh2)
    if (!firstMeshShowing) {
        globalScene1.remove( combinedIsoMesh )
    }

    if (!firstMeshShowing) globalHostDiv1.style.display = "none"

    combinedIsoMesh.material.uniforms.map2.value = null

    secondMeshShowing = false
    updateUniformsFunction()
    render1Needed = true
    render2Needed = true
}

function refreshMesh2Data() {
    
    if (usingDoubleViews) {
        globalScene2.add( isoMesh2 );
        globalScene2.add( mipMesh2 );
        globalHostDiv2.style.display = "inline-block"
    } else {
        // Note, it is possible that this code will be reached even
        // when combinedIsoMesh is already part of the scene. However, 
        // this is not an issue, and the object is not added twice. 
        // (verified by examining Object3D.add in Three.JS source code)
        globalScene1.add( combinedIsoMesh );
    }

    if (!usingDoubleViews || firstMeshShowing) globalHostDiv1.style.display = "inline-block"

    combinedIsoMesh.material.uniforms.map2.value = isoTexture2

    secondMeshShowing = true
    updateUniformsFunction()
    updateRenderer()

}

export function createMesh2fromData(data) {
    
    if (isoMesh2) isoMesh2.material.dispose() // Fix issues with lingering Uint8Array instances 
    if (mipMesh2) mipMesh2.material.dispose() 
    if (isoTexture2) isoTexture2.dispose()

    if (secondMeshShowing) clearMesh2()
    let imageData = processArrayBufferResponse(data)
    isoTexture2 = makeIsoTextureFromFloatData(...imageData)
    isoMesh2 = createIsoMesh(isoTexture2)
    mipMesh2 = createMipMesh(...imageData)
    imageData = null

    refreshMesh2Data()
    render1Needed = true
    render2Needed = true
}

/**
 * @param {ArrayBuffer} data
 * @returns {[Float32Array, number, number, number]}
 */
function processArrayBufferResponse(data) {

    let { image, headerdict } = readMrc(new Uint8Array(data));

    // convert the image data to Float32 format if needed
    if (!(image instanceof Float32Array)) {
    	const floatImage = new Float32Array(image.length);
    	for (let i=0; i<image.length; i++) {
    		floatImage[i] = image[i];
    	}
    	image = floatImage;
    }

    // normalize to the range [0,1]
	let max = image[0];
	let min = image[0];
	for (let i=0; i<image.length; i++) {
		if (image[i] > max) {
			max = image[i];
		}
		if (image[i] < min) {
			min = image[i];
		}
	}
	const f = max - min;
	for (let i=0; i<image.length; i++) {
		image[i] = (image[i] - min)/f;
	}

    return [image, headerdict["nx"], headerdict["ny"], headerdict["nz"]];
}

function moveCamera1(event) {
    // Called when camera 2 moves
    globalCamera1.position.copy(globalCamera2.position)
    globalCamera1.rotation.copy(globalCamera2.rotation)
}

function moveCamera2(event) {
    // Called when camera 1 moves
    if (globalCamera2 && usingDoubleViews) {
        globalCamera2.position.copy(globalCamera1.position)
        globalCamera2.rotation.copy(globalCamera1.rotation)
    }
}

function setup3dView(hostElement, controlsHost, size, instanceID, isMainView = true) {
    const components = create3dView(hostElement, controlsHost, size, instanceID, isMainView)
    combinedIsoMesh = createEmptyDoubleIsoMesh()
    
    if (isMainView) {
        globalCamera1 = components.camera
        globalScene1 = components.scene
        globalRenderer1 = components.renderer
        globalHostDiv1 = components.hostDiv
    } else {
        globalCamera2 = components.camera
        globalScene2 = components.scene
        globalRenderer2 = components.renderer
        globalHostDiv2 = components.hostDiv
        globalHostDiv2.style.display = "none"
    }
}

export function createViewport(hostElement, controlsHost, size) {
    currentInstanceID += 1
    setup3dView(hostElement, controlsHost, size, currentInstanceID)
    setup3dView(hostElement, controlsHost, size, currentInstanceID, false)
}

export function setUseDoubleViews(useDoubleViews) {
    if (usingDoubleViews !== useDoubleViews) {
        usingDoubleViews = useDoubleViews
        if (firstMeshShowing) {
            clearMesh1()
            refreshMesh1Data()
        }
        if (secondMeshShowing) {
            clearMesh2()
            refreshMesh2Data()
        }
        if (usingDoubleViews === true) {
            moveCamera2()
        }
    } 
    
}

/**
 * Creates the 3D view using `three.js`.
 * 
 * @param {HTMLDivElement} hostElement
 * @param {ArrayBuffer} data
 */
function create3dView(hostElement, controlsHost, size, instanceID, isMainView) {

    const myInstanceID = instanceID

    try {

        if ( WEBGL.isWebGL2Available() === false ) {
            hostElement.appendChild( WEBGL.getWebGL2ErrorMessage() );
        }

        // CREATE THE HOST ELEMENT

        const hostDiv = document.createElement('div');
        hostDiv.style.position = "relative"
        hostDiv.style.width = (isMainView ? size : 0) + "px" // Pretend the second view has no width so that it always displays properly
        hostDiv.style.display = "none"
        hostElement.appendChild(hostDiv)
        
        // CREATE CAMERA, SCENE, AND RENDERER

        const aspect = 1; 
        const camera = new THREE.PerspectiveCamera( 30, aspect, 0.1, 1000 ); 
        const orthoCamera = new THREE.OrthographicCamera(-1, 1, 1, -1, 0.1, 100); 
        camera.position.set( ...DEFAULT.CAMERA_POSITION );
        camera.up.set( 0, 0, 1 );
        orthoCamera.position.set( ...DEFAULT.CAMERA_POSITION );
        orthoCamera.up.set( 0, 0, 1 ); // In our data, z is up
        camera.zoom = 0.8
        camera.updateProjectionMatrix()
        orthoCamera.updateProjectionMatrix()

        const defaultCamera = DEFAULT.RENDERSTYLE === "iso" ? camera : orthoCamera;
        if (isMainView) currentCamera1 = defaultCamera
        else currentCamera2 = defaultCamera

        const scene = new THREE.Scene()
        const renderer = new THREE.WebGLRenderer( { alpha: true } ); // Use alpha parameter to enable transparency
        renderer.setClearColor( 0x000000, 0 ); // second param is opacity, 0 => transparent
        renderer.setPixelRatio( 0.25 * window.devicePixelRatio );
        renderer.setSize(size, size) 
        hostDiv.appendChild( renderer.domElement );
        
        const controls = new OrbitControls( camera, renderer.domElement ); // The controls always move the perspective camera
        controls.minZoom = 0.25;
        controls.maxZoom = 8;
        controls.enablePan = false;
        controls.update();
        controls.addEventListener("change", () => {
            render1Needed = true
            render2Needed = true
            isMainView ? moveCamera2() : moveCamera1()
        })

        function animate() {
            if (myInstanceID !== currentInstanceID) return; // Stop animation if a different instance has been created
            requestAnimationFrame( animate );
            if (isMainView ? render1Needed : render2Needed) {
                if (firstMeshShowing && usingDoubleViews) isoMesh1.material.uniforms.cameraPos.value.copy( camera.position );
                if (secondMeshShowing && usingDoubleViews) isoMesh2.material.uniforms.cameraPos.value.copy( camera.position );
                if ((firstMeshShowing || secondMeshShowing) && !usingDoubleViews) combinedIsoMesh.material.uniforms.cameraPos.value.copy( camera.position );

                // Adjust position of orthographic camera
                orthoCamera.position.copy(camera.position);
                orthoCamera.position.multiplyScalar( DEFAULT.CAMERA_DISTANCE / orthoCamera.position.length())
                orthoCamera.rotation.copy(camera.rotation)
                orthoCamera.zoom = 2 * DEFAULT.CAMERA_DISTANCE / camera.position.length()
                orthoCamera.updateProjectionMatrix()
                if (isMainView) renderer.render( scene, currentCamera1 );
                else renderer.render(scene, currentCamera2);
                (isMainView ? render1Needed = false : render2Needed = false)
            }
        }
        animate();

        let updateGuiOptions = () => { }

        if (isMainView) {
            // Add GUI
            const gui = new GUI({autoPlace: false});
            gui.remember(volconfig)
            gui.useLocalStorage = true

            controlsHost.appendChild(gui.domElement)

            const updateUniformsAndSave = () => { updateUniforms(); gui.saveToLocalStorageIfPossible() }

            const isoCtrl = gui.add( volconfig, 'isothreshold', 0, 1, 0.01 ).name("Contour value")   .listen().onChange(updateUniformsAndSave) // eslint-disable-line
            gui.add( volconfig, 'sharpness', 0.02, 1, 0.01 ).name("Level of detail")                   .listen().onChange( () => { updateRenderer(); updateUniformsAndSave() } );  // eslint-disable-line
            const folder = gui.addFolder('Advanced options')
            const stepsCtrl = folder.add( volconfig, 'steps', 100, 5000, 1 ).name("Sharpness")   .listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const clim1Ctrl = folder.add( volconfig, 'clim1', 0, 1, 0.01 ).name("Brightness")     .listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const clim2Ctrl = folder.add( volconfig, 'clim2', 0, 1, 0.01 ).name("Contrast")     .listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const colormapCtrl = folder.add(                                                                             // eslint-disable-line
                volconfig, 'colormap', { viridis: 'viridis', gray: 'gray'} ).name("Colormap")   .listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const renderCtrl = folder.add( volconfig, 'renderstyle', { mip: 'mip', iso: 'iso' } ).name("Render style").listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const firstColorCtrl = folder.addColor( volconfig, 'firstColor' ).name("Map 1 color")  .listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const secondColorCtrl = folder.addColor( volconfig, 'secondColor' ).name("Map 2 color").listen().onChange(updateUniformsAndSave) // eslint-disable-line
            const resetButton = gui.add(volconfig, 'reset').name("Reset to default parameters")
            
            DEFAULT.CLOSE_GUI && folder.close()
            DEFAULT.CLOSE_GUI && gui.close()

            /**
             * @param {GUIController} controller
             */
            function changeStatusOf(controller, disable, reason="") {
                const element = controller.domElement
                element.style.opacity = disable ? 0.5 : 1
                element.style.filter = disable ? "grayscale(100%)" : "none"
                element.style.pointerEvents = disable ? "none" : "auto"
                element.parentElement.title = reason
            }

            const disable = (control, param, req) => changeStatusOf(control, true, `Only available when ${param} is set to ${req}`)
            const enable = (control) => changeStatusOf(control, false)

            updateGuiOptions = () => {

                if (!usingDoubleViews) {
                    volconfig.renderstyle = "iso"
                    renderCtrl.domElement.value = "iso"
                    changeStatusOf(renderCtrl, true, `When using overlay view, only iso renderstyle is available.`)
                } else {
                    enable(renderCtrl)
                }

                if (volconfig.renderstyle === "mip") {
                    disable(firstColorCtrl, "renderstyle", "iso")
                    disable(secondColorCtrl, "renderstyle", "iso")
                    disable(isoCtrl, "renderstyle", "iso")
                    disable(stepsCtrl, "renderstyle", "iso")
                    enable(colormapCtrl)
                    enable(clim1Ctrl)
                    enable(clim2Ctrl)
                } else {
                    enable(isoCtrl)
                    enable(firstColorCtrl)
                    enable(secondColorCtrl)
                    enable(stepsCtrl)
                    disable(colormapCtrl, "renderstyle", "mip")
                    disable(clim1Ctrl, "renderstyle", "mip")
                    disable(clim2Ctrl, "renderstyle", "mip")
                }

                const paramsAreDefault = 
                    volconfig.clim1         === DEFAULT.CLIM[0]      &&
                    volconfig.clim2         === DEFAULT.CLIM[1]      &&
                    volconfig.renderstyle   === DEFAULT.RENDERSTYLE  &&
                    volconfig.isothreshold  === DEFAULT.ISOTHRESHOLD &&
                    volconfig.colormap      === DEFAULT.COLORMAP     &&
                    volconfig.sharpness     === DEFAULT.SHARPNESS    &&
                    volconfig.firstColor    === DEFAULT.FIRST_COLOR  && 
                    volconfig.secondColor   === DEFAULT.SECOND_COLOR &&
                    volconfig.steps         === DEFAULT.STEPS;
                
                if (paramsAreDefault) {
                    changeStatusOf(resetButton, true, "Parameters are already set to their default values.")
                    resetButton.domElement.parentElement.childNodes[0].style.color = "#aaa"
                    resetButton.domElement.parentElement.parentElement.classList.add("disabled")
                } else {
                    enable(resetButton)
                    resetButton.domElement.parentElement.childNodes[0].style.color = "#000"
                    resetButton.domElement.parentElement.parentElement.classList.remove("disabled")
                }

            }
            updateGuiOptions()
        } else {
            updateSecondCamera = () => {
                if (volconfig.renderstyle === "mip") {
                    currentCamera2 = orthoCamera
                } else {
                    currentCamera2 = camera
                }
            }
        }

        function updateUniforms() {

            if (firstMeshShowing) {

                isoMesh1.material.uniforms.threshold.value = volconfig.isothreshold; // For ISO renderstyle

                const color = hexToRgb(volconfig.firstColor)

                isoMesh1.material.uniforms.steps.value = volconfig.steps;
                isoMesh1.material.uniforms.redValue.value = color.r
                isoMesh1.material.uniforms.greenValue.value = color.g
                isoMesh1.material.uniforms.blueValue.value = color.b

                combinedIsoMesh.material.uniforms.threshold.value = volconfig.isothreshold; // For ISO renderstyle
                combinedIsoMesh.material.uniforms.steps.value = volconfig.steps;
                combinedIsoMesh.material.uniforms.redValue1.value = color.r
                combinedIsoMesh.material.uniforms.greenValue1.value = color.g
                combinedIsoMesh.material.uniforms.blueValue1.value = color.b

                mipMesh1.material.uniforms[ "u_cmdata" ].value = cmtextures[ volconfig.colormap ];
                mipMesh1.material.uniforms[ "u_clim" ].value.set( volconfig.clim1, volconfig.clim2 );

            }

            if (secondMeshShowing) {

                isoMesh2.material.uniforms.threshold.value = volconfig.isothreshold; // For ISO renderstyle

                const color = hexToRgb(volconfig.secondColor)

                isoMesh2.material.uniforms.steps.value = volconfig.steps;
                isoMesh2.material.uniforms.redValue.value = color.r
                isoMesh2.material.uniforms.greenValue.value = color.g
                isoMesh2.material.uniforms.blueValue.value = color.b

                combinedIsoMesh.material.uniforms.steps.value = volconfig.steps;
                combinedIsoMesh.material.uniforms.redValue2.value = color.r
                combinedIsoMesh.material.uniforms.greenValue2.value = color.g
                combinedIsoMesh.material.uniforms.blueValue2.value = color.b

                mipMesh2.material.uniforms[ "u_cmdata" ].value = cmtextures[ volconfig.colormap ];
                mipMesh2.material.uniforms[ "u_clim" ].value.set( volconfig.clim1, volconfig.clim2 );
            }

            // Disable irrelevant options, switch to correct camera
            updateGuiOptions()
            updateSecondCamera()
            if (volconfig.renderstyle === "mip") {
                currentCamera1 = orthoCamera;
                if (firstMeshShowing) {
                    mipMesh1.visible = true
                    isoMesh1.visible = false
                }
                if (secondMeshShowing) {
                    mipMesh2.visible = true
                    isoMesh2.visible = false
                }
            } else {
                currentCamera1 = camera;
                if (firstMeshShowing) {
                    mipMesh1.visible = false
                    isoMesh1.visible = true
                }
                if (secondMeshShowing) {
                    mipMesh2.visible = false
                    isoMesh2.visible = true
                }
            }

            render1Needed = true
            render2Needed = true

        }

        if (isMainView) updateUniformsFunction = updateUniforms;

        return {
            hostDiv: hostDiv, 
            camera: camera, 
            renderer: renderer, 
            scene: scene
        }

    } catch (e) {
        console.error(e)
    }
}

function updateRenderer() {
    globalRenderer1.setPixelRatio( volconfig.sharpness * window.devicePixelRatio );
    globalRenderer2.setPixelRatio( volconfig.sharpness * window.devicePixelRatio );

    render1Needed = true
    render2Needed = true
}

export function updateRendererSize(size) {
    globalRenderer1.setSize( size, size );

    const aspect = 1; // hostElement.offsetWidth / hostElement.offsetHeight;
    const frustumHeight = globalCamera1.top - globalCamera1.bottom;

    globalCamera1.left = - frustumHeight * aspect / 2;
    globalCamera1.right = frustumHeight * aspect / 2;
    globalCamera1.updateProjectionMatrix();
    globalHostDiv1.style.width = size + "px"


    globalRenderer2.setSize( size, size );

    globalCamera2.left = - frustumHeight * aspect / 2;
    globalCamera2.right = frustumHeight * aspect / 2;
    globalCamera2.updateProjectionMatrix();
    globalHostDiv2.style.width = size + "px"

    render1Needed = true
    render2Needed = true
}

export function getUserAgent() {
    return navigator.userAgent
}