from waitress import serve
from app import create_app

if __name__ == '__main__':
    application = create_app()
    print('Starting weekly-report-system on http://0.0.0.0:5000')
    serve(application, host='0.0.0.0', port=5000, threads=4)
