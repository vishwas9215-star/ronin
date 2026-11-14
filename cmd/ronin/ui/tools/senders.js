// Who sent an SMS: the sender code (the middle of "VM-HDFCBK-S") -> the company's name and colour, so the
// message list reads like the caller-ID apps (HDFC Bank in blue, Kotak in red...). Data only: add a line
// to add a company. No logos (they belong to the companies): a coloured badge with short letters.
// Codes are matched in full; "prefix" entries match the start of a code (e.g. every "SBI..." code).
window.RONIN_SENDERS = {
  exact: {
    HDFCBK: ['HDFC Bank', '#004c8f', 'HDFC'], HDFCBN: ['HDFC Bank', '#004c8f', 'HDFC'], HDFCCC: ['HDFC Bank Card', '#004c8f', 'HDFC'],
    KOTAKB: ['Kotak Bank', '#ed1c24', 'KO'], KOTAKD: ['Kotak Bank', '#ed1c24', 'KO'], KOTAKM: ['Kotak Bank', '#ed1c24', 'KO'],
    ICICIB: ['ICICI Bank', '#f37e20', 'IC'], ICICIT: ['ICICI Bank', '#f37e20', 'IC'], AXISBK: ['Axis Bank', '#97144d', 'AX'],
    PNBSMS: ['Punjab National Bank', '#a20a3a', 'PNB'], BOIIND: ['Bank of India', '#f36f21', 'BOI'], CANBNK: ['Canara Bank', '#0d7abf', 'CAN'],
    UNIONB: ['Union Bank', '#e31e24', 'UB'], BOBTXN: ['Bank of Baroda', '#f26522', 'BOB'], IDFCFB: ['IDFC First Bank', '#9c1d26', 'IDFC'],
    INDUSB: ['IndusInd Bank', '#98272a', 'IND'], YESBNK: ['Yes Bank', '#00518f', 'YES'], FINOBK: ['Fino Bank', '#00a0e3', 'FINO'],
    AIRBNK: ['Airtel Payments Bank', '#e40000', 'APB'], IPPBNK: ['India Post Payments Bank', '#d71920', 'IPPB'],
    DHANHQ: ['Dhan', '#2d5bff', 'DHAN'], FFYERS: ['Fyers', '#3b82f6', 'FY'], FYERSS: ['Fyers', '#3b82f6', 'FY'], ZERODH: ['Zerodha', '#387ed1', 'ZE'],
    GROWWS: ['Groww', '#00b386', 'GR'], UPSTOX: ['Upstox', '#5a298b', 'UP'], ANGELB: ['Angel One', '#1d3b90', 'AO'],
    NSETRA: ['NSE', '#1f4e8c', 'NSE'], NSESMS: ['NSE', '#1f4e8c', 'NSE'], NSEIPO: ['NSE', '#1f4e8c', 'NSE'], BSELTD: ['BSE', '#b8860b', 'BSE'],
    BSESMS: ['BSE', '#b8860b', 'BSE'], CDSLTX: ['CDSL', '#1e3a8a', 'CDSL'], CDSLEV: ['CDSL', '#1e3a8a', 'CDSL'], NSDLCA: ['NSDL', '#0f5132', 'NSDL'],
    JIOHTT: ['JioHotstar', '#0f1d6b', 'JH'], JIOINF: ['Jio', '#0f3cc9', 'JIO'], JIOPAY: ['Jio', '#0f3cc9', 'JIO'], AIRTEL: ['Airtel', '#e40000', 'AIR'],
    VICARE: ['Vi', '#e91e63', 'Vi'], BSNLIN: ['BSNL', '#1c75bc', 'BSNL'], LICHFL: ['LIC Housing Finance', '#0055a5', 'LIC'], LICIND: ['LIC', '#0055a5', 'LIC'],
    UPPCLT: ['UPPCL (Electricity)', '#0b7a3e', '⚡'], UIDAIG: ['UIDAI (Aadhaar)', '#b45309', 'AAD'], EPFOHO: ['EPFO', '#1e40af', 'PF'],
    IRCTCI: ['IRCTC', '#213a8f', 'RAIL'], PAYTMB: ['Paytm', '#00baf2', 'PAY'], PHONPE: ['PhonePe', '#5f259f', 'PE'], GPAYIN: ['Google Pay', '#1a73e8', 'GP'],
    AMAZON: ['Amazon', '#232f3e', 'AMZ'], AMZNIN: ['Amazon', '#232f3e', 'AMZ'], FLPKRT: ['Flipkart', '#2874f0', 'FK'], SWIGGY: ['Swiggy', '#fc8019', 'SW'],
    ZOMATO: ['Zomato', '#e23744', 'ZO'], BLUDRT: ['Blue Dart', '#004a99', 'BD'], DLVERY: ['Delhivery', '#d52b1e', 'DEL'], TMANGO: ['Mango (OTP)', '#475569', 'OTP'],
  },
  prefix: [
    ['SBI', 'State Bank of India', '#22409a', 'SBI'], ['HDFC', 'HDFC Bank', '#004c8f', 'HDFC'], ['KOTAK', 'Kotak Bank', '#ed1c24', 'KO'], ['ICICI', 'ICICI Bank', '#f37e20', 'IC'],
    ['AXIS', 'Axis Bank', '#97144d', 'AX'], ['NSE', 'NSE', '#1f4e8c', 'NSE'], ['BSE', 'BSE', '#b8860b', 'BSE'], ['CDSL', 'CDSL', '#1e3a8a', 'CDSL'],
    ['JIO', 'Jio', '#0f3cc9', 'JIO'], ['AIRTEL', 'Airtel', '#e40000', 'AIR'], ['LIC', 'LIC', '#0055a5', 'LIC'], ['DHAN', 'Dhan', '#2d5bff', 'DHAN'],
    ['FYERS', 'Fyers', '#3b82f6', 'FY'], ['PAYTM', 'Paytm', '#00baf2', 'PAY'], ['IRCTC', 'IRCTC', '#213a8f', 'RAIL'],
  ],
};
