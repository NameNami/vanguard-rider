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


// --- HELPER AND MEMOIZED COMPONENTS ---

const ChangeView = ({ center, zoom }) => {
  const map = useMap();
  useEffect(() => {
    map.setView(center, zoom);
  }, [center, zoom, map]);
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
            speed = 0, label = 0 } = stats || {};
            
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
        let color = '#6c757d';
        if (status === 'Connected' || status === 'Device Connected') color = '#198754';
        if (status === 'Disconnected' || status === 'Device Disconnected') color = '#dc3545';
        return {
            fontWeight: 'bold', color: 'white', padding: '5px 10px',
            borderRadius: '5px', backgroundColor: color, display: 'inline-block',
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

const TripSelector = memo(({ activeTrips, selectedTrip, onTripSelect }) => {
    return (
        <div style={{ marginBottom: '15px' }}>
            <label htmlFor="trip-select" style={{ fontWeight: 'bold', marginRight: '10px', fontSize: '1.2em' }}>Monitor Trip:</label>
            <select
                id="trip-select"
                value={selectedTrip}
                onChange={(e) => onTripSelect(e.target.value)}
                style={{ padding: '8px', fontSize: '1em', borderRadius: '5px' }}
            >
                <option value="all">All Trips (Overview)</option>
                {activeTrips.map(tripId => (
                    <option key={tripId} value={tripId}>{tripId.substring(0, 8)}...</option>
                ))}
            </select>
        </div>
    );
});

const DetailPlaceholder = ({ text }) => {
    return (
        <div style={{
            height: '100%', minHeight: '200px', display: 'flex',
            alignItems: 'center', justifyContent: 'center',
            background: '#f8f9fa', border: '1px solid #ccc', borderRadius: '5px',
            textAlign: 'center', color: '#6c757d'
        }}>
            <p style={{ fontSize: '1.2em', padding: '10px' }}>{text}</p>
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

function App() {
    const [latestData, setLatestData] = useState(initialLatestData);
    const [position, setPosition] = useState([3.1390, 101.6869]);
    const [chartData, setChartData] = useState({ labels: [], datasets: [] });
    const [backendStatus, setBackendStatus] = useState("Connecting...");
    const [deviceStatus, setDeviceStatus] = useState("Unknown");
    const [activeTrips, setActiveTrips] = useState([]);
    const [selectedTrip, setSelectedTrip] = useState("all");
    const [tripPaths, setTripPaths] = useState({});
    
    const chartDataRefs = useRef({});

    // This useEffect hook handles all real-time communication
    useEffect(() => {
        const eventSource = new EventSource("http://localhost:8080/sse");
        
        eventSource.onopen = () => {
            console.log("✅ SSE Connection Established!");
            setBackendStatus("Connected");
        };

        eventSource.addEventListener('telematics-update', (event) => {
            const data = JSON.parse(event.data);
            const tripId = data.tripId;

            // Always update the background path data for every trip
            if (data.latitude && data.longitude && data.latitude !== 0) {
                const newPosition = [data.latitude, data.longitude];
                setTripPaths(prevPaths => {
                    const currentPath = prevPaths[tripId] || [];
                    const newPath = [...currentPath, newPosition];
                    return { ...prevPaths, [tripId]: newPath };
                });
            }

            // Only update the main display if the trip is selected (or in overview mode)
            if (selectedTrip === 'all' || tripId === selectedTrip) {
                setLatestData(data);
                if (data.latitude && data.longitude && data.latitude !== 0) {
                    setPosition([data.latitude, data.longitude]);
                }

                // Manage chart data
                const chartKey = selectedTrip === 'all' ? 'all' : tripId;
                if (!chartDataRefs.current[chartKey]) {
                    chartDataRefs.current[chartKey] = { labels: [], accData: [], speedData: [] };
                }
                const tripChart = chartDataRefs.current[chartKey];
                const now = new Date(data.timestamp).toLocaleTimeString();
                tripChart.labels.push(now);
                tripChart.accData.push(data.accMag);
                tripChart.speedData.push(data.speed * 3.6);

                if (tripChart.labels.length > 50) {
                    tripChart.labels.shift();
                    tripChart.accData.shift();
                    tripChart.speedData.shift();
                }

                setChartData({
                    labels: [...tripChart.labels],
                    datasets: [
                        { label: 'Speed (km/h)', data: [...tripChart.speedData], borderColor: 'rgb(75, 192, 192)', yAxisID: 'y' },
                        { label: 'Accel. Mag (m/s²)', data: [...tripChart.accData], borderColor: 'rgb(255, 99, 132)', yAxisID: 'y1' }
                    ]
                });
            }
        });
        
        eventSource.addEventListener('trip-list-update', (event) => {
            const tripData = JSON.parse(event.data);
            setActiveTrips(tripData.activeTrips);
        });

        eventSource.addEventListener('status-update', (event) => {
            const statusData = JSON.parse(event.data);
            setDeviceStatus(statusData.deviceStatus);
        });

        eventSource.onerror = (err) => {
            console.error("EventSource failed:", err);
            setBackendStatus("Disconnected");
            setDeviceStatus("Unknown");
        };
        
        return () => {
            console.log("Closing SSE connection.");
            eventSource.close();
        };
    }, [selectedTrip]); // Dependency array makes the filtering logic re-evaluate when you change the dropdown

    const { rotVecX, rotVecY, rotVecZ, rotVecW } = latestData;
    const isTripSelected = selectedTrip !== 'all';
    const displayedPath = isTripSelected ? (tripPaths[selectedTrip] || []) : [];

    return (
        <div style={{ padding: '20px', fontFamily: 'Arial, sans-serif' }}>
            <h1>Vanguard Rider - Live Telematics Dashboard</h1>
            
            <ConnectionStatusIndicator backendStatus={backendStatus} deviceStatus={deviceStatus} />
            <TripSelector activeTrips={activeTrips} selectedTrip={selectedTrip} onTripSelect={setSelectedTrip} />
            
            <div style={{ 
                display: 'grid', 
                gridTemplateColumns: '1fr 1fr',
                gap: '20px',
                alignItems: 'start'
            }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    <div>
                        <h2>Live Map</h2>
                        <MemoizedMap position={position} tripPath={displayedPath} />
                    </div>
                    <div>
                        <h2>Live Phone Orientation</h2>
                        {isTripSelected ? (
                            <Memoized3DView rotationData={rotVecX !== undefined ? [rotVecX, rotVecY, rotVecZ, rotVecW] : null} />
                        ) : (
                            <DetailPlaceholder text="Select a trip to see its live orientation." />
                        )}
                    </div>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    <div>
                        <h2>Sensor Readings Over Time</h2>
                        {isTripSelected || activeTrips.length > 0 ? (
                            <MemoizedChart chartData={chartData} />
                        ) : (
                             <DetailPlaceholder text="Waiting for trip data..." />
                        )}
                    </div>
                    <div>
                        <h2>Live Stats</h2>
                        {isTripSelected ? (
                            <LiveStats stats={latestData} />
                        ) : (
                            <div style={{fontSize: '1.1em', padding: '10px', border: '1px solid #eee', borderRadius: '5px' }}>
                                <p><strong>Active Trips:</strong> {activeTrips.length}</p>
                                <p>Currently showing overview. The map marker will show the latest GPS location from any trip.</p>
                                <p>Please select a trip from the dropdown to see its specific details.</p>
                            </div>
                        )}
                    </div>
                </div>
            </div>
            {/* No EventMarker component is needed in this monitor-only version */}
        </div>
    );
}

export default App;