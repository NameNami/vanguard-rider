// file: frontend/src/App.js
import React, { useState, useEffect, useRef, memo } from 'react';
import 'leaflet/dist/leaflet.css';
import { Chart, registerables } from 'chart.js';
import L from 'leaflet';
import { MapContainer, TileLayer, Marker, Polyline, useMap } from 'react-leaflet';
import { Line } from 'react-chartjs-2';
import Phone3DView from './Phone3DView';

Chart.register(...registerables);

// Fix for default marker icon in Leaflet
delete L.Icon.Default.prototype._getIconUrl;
L.Icon.Default.mergeOptions({
    iconRetinaUrl: require('leaflet/dist/images/marker-icon-2x.png'),
    iconUrl: require('leaflet/dist/images/marker-icon.png'),
    shadowUrl: require('leaflet/dist/images/marker-shadow.png'),
});


// --- HELPER AND MEMOIZED COMPONENTS (UNCHANGED) ---

const ChangeView = ({ center, zoom }) => {
  const map = useMap();
  map.setView(center, zoom);
  return null;
};

const MemoizedMap = memo(({ position, tripPath }) => {
    return (
        <div style={{ height: '350px', width: '100%', border: '1px solid #ccc' }}>
            <MapContainer center={position} zoom={16} style={{ height: '100%', width: '100%' }}>
                <ChangeView center={position} zoom={16} />
                <TileLayer url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
                <Marker position={position} />
                <Polyline positions={tripPath} color="blue" />
            </MapContainer>
        </div>
    );
});

const Memoized3DView = memo(({ rotationData }) => {
    return (
        <div style={{ height: '350px', width: '100%' }}>
            <Phone3DView rotationData={rotationData} />
        </div>
    );
});

const MemoizedChart = memo(({ chartData }) => {
    return <Line data={chartData} options={{
        animation: false,
        scales: {
            y: { type: 'linear', position: 'left', title: { display: true, text: 'Speed (km/h)' } },
            y1: { type: 'linear', position: 'right', title: { display: true, text: 'Accel. Mag (m/s²)' }, grid: { drawOnChartArea: false } }
        }
    }}/>;
});

const LiveStats = memo(({ stats }) => {
    const { accX = 0, accY = 0, accZ = 0, accMag = 0,
            gyroX = 0, gyroY = 0, gyroZ = 0,
            rotVecX, rotVecY, rotVecZ, rotVecW,
            speed = 0, label = 0 } = stats;
            
    const labelMap = { 0: "Normal", 1: "Harsh Brake", 2: "Harsh Cornering", 3: "Pothole", 4: "Accident", 5: "Phone Fall"};

    return (
        <div style={{ fontSize: '1.1em', padding: '10px', border: '1px solid #eee', borderRadius: '5px' }}>
            <p><strong>Status:</strong> <span style={{fontWeight: 'bold', color: label === 0 ? 'green' : 'red'}}>{labelMap[label] || "Unknown"}</span></p>
            <p><strong>Speed:</strong> {(speed * 3.6).toFixed(2)} km/h</p>
            <hr style={{margin: '10px 0'}}/>
            <p><strong>Accel X | Y | Z:</strong> {accX.toFixed(2)}, {accY.toFixed(2)}, {accZ.toFixed(2)}</p>
            <p><strong>Accel Mag:</strong> {accMag.toFixed(2)} m/s²</p>
            <hr style={{margin: '10px 0'}}/>
            <p><strong>Gyro X | Y | Z:</strong> {gyroX.toFixed(2)}, {gyroY.toFixed(2)}, {gyroZ.toFixed(2)}</p>
            <hr style={{margin: '10px 0'}}/>
            <p><strong>RotVec X | Y | Z | W:</strong> {(rotVecX || 0).toFixed(2)}, {(rotVecY || 0).toFixed(2)}, {(rotVecZ || 0).toFixed(2)}, {(rotVecW || 0).toFixed(2)}</p>
        </div>
    );
});

const ConnectionStatusIndicator = ({ backendStatus, deviceStatus }) => {
    const getStatusStyle = (status) => {
        let color = '#6c757d'; // Gray for Unknown/Connecting
        if (status === 'Connected' || status === 'Device Connected') color = '#198754'; // Green
        if (status === 'Disconnected' || status === 'Device Disconnected') color = '#dc3545'; // Red
        return {
            fontWeight: 'bold',
            color: 'white',
            padding: '5px 10px',
            borderRadius: '5px',
            backgroundColor: color,
            display: 'inline-block',
            marginLeft: '10px'
        };
    };

    return (
        <div style={{fontSize: '1.2em', marginBottom: '15px'}}>
            <span><strong>Backend Status:</strong> <span style={getStatusStyle(backendStatus)}>{backendStatus}</span></span>
            <span style={{marginLeft: '20px'}}><strong>Device Status:</strong> <span style={getStatusStyle(deviceStatus)}>{deviceStatus}</span></span>
        </div>
    );
};


// --- MAIN APP COMPONENT ---

const initialLatestData = {
    accX: 0, accY: 0, accZ: 0, accMag: 0,
    gyroX: 0, gyroY: 0, gyroZ: 0, gyroMag: 0,
    rotVecX: 0, rotVecY: 0, rotVecZ: 0, rotVecW: 1,
    speed: 0, label: 0, latitude: 3.1390, longitude: 101.6869
};
const initialState = {
    latestData: initialLatestData,
    position: [3.1390, 101.6869],
    tripPath: [],
    chartData: { labels: [], datasets: [] }
};

function App() {
    const [dashboardState, setDashboardState] = useState(initialState);
    const [backendStatus, setBackendStatus] = useState("Connecting...");
    const [deviceStatus, setDeviceStatus] = useState("Unknown");
    const chartDataRef = useRef({ labels: [], accData: [], speedData: [] });

    // eslint-disable-next-line react-hooks/exhaustive-deps
    useEffect(() => {
        const eventSource = new EventSource("http://localhost:8080/sse");
        
        eventSource.onopen = () => {
            console.log("✅ SSE Connection Established!");
            setBackendStatus("Connected");
        };

        // --- UPDATED: Use addEventListener for custom events ---
        eventSource.addEventListener('telematics-update', (event) => {
            const data = JSON.parse(event.data);
            
            let newPosition = dashboardState.position;
            let newTripPath = dashboardState.tripPath;
            if (data.latitude && data.longitude && data.latitude !== 0) {
                newPosition = [data.latitude, data.longitude];
                if (newTripPath.length === 0 || newTripPath[newTripPath.length - 1][0] !== newPosition[0]) {
                   newTripPath = [...newTripPath, newPosition];
                }
            }

            const now = new Date(data.timestamp).toLocaleTimeString();
            chartDataRef.current.labels.push(now);
            chartDataRef.current.accData.push(data.accMag);
            chartDataRef.current.speedData.push(data.speed * 3.6);

            if (chartDataRef.current.labels.length > 50) {
                chartDataRef.current.labels.shift();
                chartDataRef.current.accData.shift();
                chartDataRef.current.speedData.shift();
            }
            
            const newChartData = {
                labels: [...chartDataRef.current.labels],
                datasets: [
                    { label: 'Speed (km/h)', data: [...chartDataRef.current.speedData], borderColor: 'rgb(75, 192, 192)', yAxisID: 'y' },
                    { label: 'Accel. Mag (m/s²)', data: [...chartDataRef.current.accData], borderColor: 'rgb(255, 99, 132)', yAxisID: 'y1' }
                ]
            };

            setDashboardState({
                latestData: data,
                position: newPosition,
                tripPath: newTripPath,
                chartData: newChartData
            });
        });

        eventSource.addEventListener('status-update', (event) => {
            const statusData = JSON.parse(event.data);
            console.log("Received device status:", statusData.deviceStatus);
            setDeviceStatus(statusData.deviceStatus);
        });

        eventSource.onerror = (err) => {
            console.error("EventSource failed:", err);
            setBackendStatus("Disconnected");
            setDeviceStatus("Unknown");
            eventSource.close();
        };
        
        return () => {
            console.log("Closing SSE connection.");
            eventSource.close();
        };
    }, []);

    const { latestData, position, tripPath, chartData } = dashboardState;
    const { rotVecX, rotVecY, rotVecZ, rotVecW } = latestData;

    return (
        <div style={{ padding: '20px', fontFamily: 'Arial, sans-serif' }}>
            <h1>Vanguard Rider - Live Telematics Dashboard</h1>
            
            <ConnectionStatusIndicator backendStatus={backendStatus} deviceStatus={deviceStatus} />
            
            <div style={{ 
                display: 'grid', 
                gridTemplateColumns: '1fr 1fr',
                gap: '20px',
                alignItems: 'start'
            }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    <div>
                        <h2>Live Map</h2>
                        <MemoizedMap position={position} tripPath={tripPath} />
                    </div>
                    <div>
                        <h2>Live Phone Orientation</h2>
                        <Memoized3DView rotationData={rotVecX !== undefined ? [rotVecX, rotVecY, rotVecZ, rotVecW] : null} />
                    </div>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    <div>
                        <h2>Sensor Readings Over Time</h2>
                        <MemoizedChart chartData={chartData} />
                    </div>
                    <div>
                        <h2>Live Stats</h2>
                        <LiveStats stats={latestData} />
                    </div>
                </div>
            </div>
        </div>
    );
}

export default App;