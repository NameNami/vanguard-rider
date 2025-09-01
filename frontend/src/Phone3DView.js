// file: frontend/src/Phone3DView.js
import React, { useRef } from 'react';
import { Canvas, useFrame } from '@react-three/fiber';
import { Box, OrbitControls } from '@react-three/drei';

// This component contains the rotating box (the phone model)
function PhoneModel({ rotationQuaternion }) {
    const boxRef = useRef();

    // useFrame is a hook that runs on every single rendered frame
    useFrame(() => {
        if (boxRef.current && rotationQuaternion) {
            // Directly set the 3D object's rotation
            // using the quaternion data from the phone's rotation vector.
            const [x, y, z, w] = rotationQuaternion;
            boxRef.current.quaternion.set(x, y, z, w);
        }
    });

    return (
        <Box ref={boxRef} args={[1.5, 3, 0.2]}> {/* Dimensions of a phone-like box */}
            <meshStandardMaterial color={'#333'} />
        </Box>
    );
}

// This is the main component that sets up the 3D scene.
// The Canvas is now the top-level component and will fill its parent div.
export default function Phone3DView({ rotationData }) {
    return (
        <Canvas camera={{ position: [0, 2, 5] }}>
            <ambientLight intensity={0.5} />
            <pointLight position={[10, 10, 10]} />
            <PhoneModel rotationQuaternion={rotationData} />
            <OrbitControls /> {/* Allows you to rotate the scene with your mouse */}
        </Canvas>
    );}