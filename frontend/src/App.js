// file: frontend/src/App.js
import React, { useState, useEffect, useRef, memo } from 'react';
import { MapContainer, TileLayer, Polyline, Marker, useMap } from 'react-leaflet';
import { Canvas } from '@react-three/fiber';
import { Box, OrbitControls } from '@react-three/drei';
import { Line } from 'react-chartjs-2';
import {
  Chart as ChartJS, CategoryScale, LinearScale, PointElement,
  LineElement, Title, Tooltip, Legend, Filler
} from 'chart.js';
import 'leaflet/dist/leaflet.css';
import './App.css';

// Register all necessary Chart.js components
ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, Title, Tooltip, Legend, Filler);

// --- NEW: ADDED PREDICTION LABEL MAPPING ---
// Maps the numerical prediction from the backend to a human-readable string.
const predictionLabels = {
    0: 'Normal',
    1: 'Harsh Braking',
    2: 'Harsh Cornering',
    3: 'Bump/Pothole',
    4: 'Accident',
    5: 'Phone Fall'
};

// --- HELPER & MEMOIZED UI COMPONENTS ---

// 1. Helper to auto-center the map view
const ChangeView = memo(({ center }) => {
    const map = useMap();
    map.setView(center, map.getZoom());
    return null;
});

// 2. 3D Phone Model for the orientation view
function PhoneModel({ rotationQuaternion }) {
    const boxRef = useRef();
    useEffect(() => {
        if (boxRef.current && rotationQuaternion) {
            const [x, y, z, w] = rotationQuaternion;
            boxRef.current.quaternion.set(x, y, z, w);
        }
    }, [rotationQuaternion]);

    return (
        <Box ref={boxRef} args={[1.5, 3, 0.2]}>
            <meshStandardMaterial color={'#555'} emissive={'#1a1a2e'} roughness={0.3} metalness={0.6} />
        </Box>
    );
}

// 3. The dynamic Speeding Alert Panel
const SpeedAlertPanel = memo(({ alertMessage }) => {
    if (!alertMessage || !alertMessage.toLowerCase().startsWith('speeding')) {
        return null;
    }
    return (
        <div className="widget-box speed-alert-panel">
            <h3>SPEED ALERT</h3>
            <p>{alertMessage}</p>
        </div>
    );
});

// 4. The Event Log Panel
const EventLogPanel = memo(({ events }) => (
    <div className="widget-box event-log-panel">
        <h3>Event Log</h3>
        {events.length === 0 ? (
            <p className="no-events-placeholder">No significant events triggered yet.</p>
        ) : (
            <ul>
                {events.map((item, index) => (
                    <li key={index}>
                        <span className="event-name">{item.event}</span>
                        <span className="event-time">{item.time}</span>
                    </li>
                ))}
            </ul>
        )}
    </div>
));

// 5. Backend Connection Status Indicator
const ConnectionStatusIndicator = memo(({ deviceStatus }) => (
    <div className={`connection-status ${deviceStatus === 'Device Connected' ? 'connected' : 'disconnected'}`}>
        <span></span> {deviceStatus}
    </div>
));

// --- MAIN APP COMPONENT ---

const initialLatestData = {
    accMag: 0, gyroMag: 0, speed: 0, latitude: 3.1390, longitude: 101.6869,
    rotVecX: 0, rotVecY: 0, rotVecZ: 0, rotVecW: 1
};
const MAX_CHART_POINTS = 50;

function App() {
    // --- STATE MANAGEMENT ---
    const [latestData, setLatestData] = useState(initialLatestData);
    const [position, setPosition] = useState([3.1390, 101.6869]);
    const [tripHistory, setTripHistory] = useState([]);
    const [deviceStatus, setDeviceStatus] = useState('Connecting...');
    const [currentPrediction, setCurrentPrediction] = useState('Initializing...');
    const [speedAlert, setSpeedAlert] = useState('OK');
    const [chartData, setChartData] = useState({
        labels: Array(MAX_CHART_POINTS).fill(''),
        datasets: [
            { label: 'Accelerometer', data: Array(MAX_CHART_POINTS).fill(0), borderColor: '#ff6384', backgroundColor: '#ff638433', fill: true, tension: 0.4 },
            { label: 'Gyroscope', data: Array(MAX_CHART_POINTS).fill(0), borderColor: '#36a2eb', backgroundColor: '#36a2eb33', fill: true, tension: 0.4 },
        ],
    });
    const [triggeredEvents, setTriggeredEvents] = useState([]);

    // useRef to hold the previous prediction value without causing re-renders
    const previousPredictionRef = useRef('Initializing...');

    // --- REAL-TIME DATA HANDLING ---
    useEffect(() => {
        const eventSource = new EventSource("http://localhost:8080/sse");

        eventSource.onopen = () => setDeviceStatus('Connecting...');
        eventSource.onerror = () => setDeviceStatus('Server Error');

        eventSource.addEventListener('telematics-update', (event) => {
            const data = JSON.parse(event.data);
            
            // --- MODIFIED: Translate the numerical prediction to its label ---
            const newPrediction = predictionLabels[data.prediction] || 'N/A';
            
            // --- LOGIC FOR THE EVENT LOG ---
            // A new event is logged only if the prediction changes to something significant.
            const isSignificantEvent = !['Normal', 'Buffering...', 'N/A'].includes(newPrediction);
            
            // Compare with the *previous* prediction to only log the change.
            if (isSignificantEvent && newPrediction !== previousPredictionRef.current) {
                const newEvent = {
                    event: newPrediction,
                    time: new Date().toLocaleTimeString()
                };
                setTriggeredEvents(prevEvents => [newEvent, ...prevEvents].slice(0, 5));
            }
            
            // Update the previous prediction ref for the next comparison.
            previousPredictionRef.current = newPrediction;
            
            // --- Update all other states ---
            setLatestData(data);
            setCurrentPrediction(newPrediction);
            setSpeedAlert(data.alert || 'OK');

            const lat = data.snapped_latitude || data.latitude;
            const lon = data.snapped_longitude || data.longitude;
            if (lat && lon) {
                const newPosition = [lat, lon];
                setPosition(newPosition);
                setTripHistory(prev => [...prev, newPosition]);
            }

            setChartData(prev => {
                const newLabels = [...prev.labels.slice(1), new Date().toLocaleTimeString()];
                const newAccData = [...prev.datasets[0].data.slice(1), data.accMag];
                const newGyroData = [...prev.datasets[1].data.slice(1), data.gyroMag];
                return {
                    labels: newLabels,
                    datasets: [{ ...prev.datasets[0], data: newAccData }, { ...prev.datasets[1], data: newGyroData }]
                };
            });
        });

        eventSource.addEventListener('status-update', (event) => {
            setDeviceStatus(JSON.parse(event.data).deviceStatus);
        });

        return () => { eventSource.close(); };
    }, []); // This effect runs only once on component mount.

    // --- DYNAMIC STYLING LOGIC ---
    const getPredictionClass = (prediction) => {
        if (!prediction) return 'status-buffering';
        const p_lower = prediction.toLowerCase();
        if (p_lower.includes('accident') || p_lower.includes('fall')) return 'status-critical';
        if (p_lower.includes('braking') || p_lower.includes('pothole') || p_lower.includes('cornering')) return 'status-warning';
        if (p_lower.includes('normal')) return 'status-normal';
        return 'status-buffering';
    };
    
    const rotationQuaternion = [latestData.rotVecX, latestData.rotVecY, latestData.rotVecZ, latestData.rotVecW];
    const chartOptions = { responsive: true, maintainAspectRatio: false, animation: { duration: 0 }, scales: { y: { beginAtZero: true } } };

    // --- JSX LAYOUT ---
    return (
        <div className="App">
            <header className="App-header">
                <h1>Vanguard Rider - Live Telematics Dashboard</h1>
                <ConnectionStatusIndicator deviceStatus={deviceStatus} />
            </header>
            <main className="main-content">
                <div className="left-panel">
                    <SpeedAlertPanel alertMessage={speedAlert} />
                    <EventLogPanel events={triggeredEvents} />
                    <div className="widget-box live-status-panel">
                        <h3>Live AI Status</h3>
                        <p className={`status-text ${getPredictionClass(currentPrediction)}`}>{currentPrediction}</p>
                    </div>
                    <div className="widget-box">
                        <h3>Live Stats</h3>
                        <div className="stats-grid">
                            <div className="stat-item"><strong>Accel Mag:</strong> <span>{latestData.accMag?.toFixed(2)} m/s²</span></div>
                            <div className="stat-item"><strong>Gyro Mag:</strong> <span>{latestData.gyroMag?.toFixed(2)} rad/s</span></div>
                            <div className="stat-item"><strong>Speed:</strong> <span>{(latestData.speed * 3.6)?.toFixed(1)} km/h</span></div>
                            <div className="stat-item"><strong>Coords:</strong> <span>{latestData.latitude?.toFixed(4)}, {latestData.longitude?.toFixed(4)}</span></div>
                        </div>
                    </div>
                    <div className="widget-box">
                        <h3>Phone Orientation</h3>
                        <Canvas camera={{ position: [0, 2, 5], fov: 75 }}>
                            <ambientLight intensity={0.6} />
                            <pointLight position={[10, 10, 10]} intensity={1.5} />
                            <PhoneModel rotationQuaternion={rotationQuaternion} />
                            <OrbitControls />
                        </Canvas>
                    </div>
                </div>
                <div className="right-panel">
                    <div className="widget-box map-box">
                        <h3>Live Trip Map</h3>
                        <MapContainer center={position} zoom={16} style={{ height: '100%', width: '100%' }}>
                             <ChangeView center={position} />
                            <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" attribution='&copy; OpenStreetMap contributors'/>
                            <Polyline positions={tripHistory} color="#36a2eb" weight={5} />
                            <Marker position={position}></Marker>
                        </MapContainer>
                    </div>
                    <div className="widget-box chart-box">
                        <h3>Live Sensor Data</h3>
                        <div className="chart-container">
                            <Line data={chartData} options={chartOptions} />
                        </div>
                    </div>
                </div>
            </main>
        </div>
    );
}

export default App;